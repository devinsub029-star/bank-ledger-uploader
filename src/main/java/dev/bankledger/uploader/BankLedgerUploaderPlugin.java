package dev.bankledger.uploader;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Uploads what Dude, Where's My Stuff tracks to a Bank Ledger profile.
 *
 * <p>DWMS publishes its storages to other plugins over RuneLite's event bus: post a
 * "storages-request" plugin message and it answers with a "storages-response" holding every
 * enabled storage with canonical item ids. This plugin asks when the bank closes and on a timer,
 * and uploads only when the merged bank differs from the last one it sent. Nothing about DWMS is
 * modified; it only has to be installed and enabled.
 */
@Slf4j
@PluginDescriptor(
    name = "Bank Ledger Uploader",
    description = "Uploads your Dude, Where's My Stuff storages to Bank Ledger",
    tags = {"bank", "value", "worth", "tracker", "dwms", "export"})
public class BankLedgerUploaderPlugin extends Plugin {
  static final String DWMS_NAMESPACE = "dudewheresmystuff";
  static final String REQUEST = "storages-request";
  static final String RESPONSE = "storages-response";
  static final String SOURCE = "Bank Ledger Uploader";
  static final String VERSION = "1.0.0";

  /** How long DWMS gets to answer before we decide it isn't there. */
  private static final long RESPONSE_TIMEOUT_MS = 10_000;
  /** After a rate limit or read-only reply, wait this long before trying again. */
  private static final long DEFER_MS = 15 * 60_000;

  @Inject private Client client;
  @Inject private ClientThread clientThread;
  @Inject private EventBus eventBus;
  @Inject private ConfigManager configManager;
  @Inject private ChatMessageManager chatMessageManager;
  @Inject private BankLedgerUploaderConfig config;
  @Inject private OkHttpClient okHttpClient;
  @Inject private Gson gson;

  private BankLedgerClient bankLedger;

  /** Fingerprint of the last bank the site accepted, per RuneScape profile. */
  private final Map<String, String> lastSent = new HashMap<>();

  private volatile long lastRequestAt;
  private volatile long pendingSince;
  private volatile long deferredUntil;
  private volatile boolean uploading;
  private volatile boolean rejected;
  private boolean warnedMissingDwms;
  private boolean warnedStale;

  @Provides
  BankLedgerUploaderConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(BankLedgerUploaderConfig.class);
  }

  @Override
  protected void startUp() {
    bankLedger = new BankLedgerClient(okHttpClient, gson);
    rejected = false;
    warnedMissingDwms = false;
  }

  @Override
  protected void shutDown() {
    lastSent.clear();
    pendingSince = 0;
  }

  @Subscribe
  public void onConfigChanged(ConfigChanged event) {
    if (BankLedgerUploaderConfig.GROUP.equals(event.getGroup())) {
      // New settings deserve a fresh try, and a first upload.
      rejected = false;
      deferredUntil = 0;
      lastSent.clear();
    }
  }

  @Subscribe
  public void onGameStateChanged(GameStateChanged event) {
    if (event.getGameState() == GameState.LOGIN_SCREEN) {
      warnedStale = false;
      pendingSince = 0;
    }
  }

  @Subscribe
  public void onWidgetClosed(WidgetClosed event) {
    if (event.getGroupId() == InterfaceID.BANKMAIN && config.uploadOnBankClose()) {
      // After DWMS has handled the same close and recorded the bank.
      clientThread.invokeLater(this::requestStorages);
    }
  }

  @Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
  public void everyMinute() {
    long now = System.currentTimeMillis();
    if (pendingSince > 0 && now - pendingSince > RESPONSE_TIMEOUT_MS) {
      pendingSince = 0;
      if (!warnedMissingDwms) {
        warnedMissingDwms = true;
        chat("Bank Ledger Uploader needs the Dude, Where's My Stuff? plugin installed and enabled.");
      }
    }
    int interval = config.intervalMinutes();
    if (interval > 0 && now - lastRequestAt >= interval * 60_000L) {
      clientThread.invokeLater(this::requestStorages);
    }
  }

  /** Asks DWMS for its storages. Client thread. */
  private void requestStorages() {
    if (!readyToUpload()) {
      return;
    }
    lastRequestAt = System.currentTimeMillis();
    pendingSince = lastRequestAt;
    Map<String, Object> data = new HashMap<>();
    data.put("source", SOURCE);
    eventBus.post(new PluginMessage(DWMS_NAMESPACE, REQUEST, data));
  }

  @Subscribe
  public void onPluginMessage(PluginMessage message) {
    if (!DWMS_NAMESPACE.equals(message.getNamespace())
        || !RESPONSE.equals(message.getName())
        || message.getData() == null
        || !SOURCE.equals(message.getData().get("target"))) {
      return;
    }
    pendingSince = 0;
    warnedMissingDwms = false;
    if (!readyToUpload()) {
      return;
    }

    StoragePayload payload;
    try {
      payload = StoragePayload.fromResponse(message.getData());
    } catch (IllegalArgumentException e) {
      log.warn("Unexpected Dude, Where's My Stuff response: {}", e.getMessage());
      return;
    }
    if (payload.isEmpty()) {
      return; // DWMS hasn't loaded this account's data yet
    }

    String profile = Objects.toString(configManager.getRSProfileKey(), "");
    String fingerprint = payload.fingerprint();
    if (fingerprint.equals(lastSent.get(profile))) {
      return; // nothing changed since the last upload
    }

    HttpUrl url = BankLedgerClient.uploadUrl(config.siteUrl(), config.profileId());
    if (url == null) {
      return;
    }
    String player = config.sendPlayerName() ? localPlayerName() : null;
    uploading = true;
    bankLedger.upload(url, config.writeKey(), payload.toRequestBody(player, VERSION),
        result -> onUploaded(result, profile, fingerprint));
  }

  /** OkHttp thread. */
  private void onUploaded(UploadResult result, String profile, String fingerprint) {
    uploading = false;
    switch (result.getOutcome()) {
      case STORED:
        lastSent.put(profile, fingerprint);
        if (config.chatMessages()) {
          chat(String.format("Bank uploaded to Bank Ledger (%,d gp).", result.getActualWorth()));
        }
        break;
      case UNCHANGED:
        lastSent.put(profile, fingerprint);
        break;
      case REJECTED:
        rejected = true;
        chat(result.getMessage() + " - check the Bank Ledger Uploader settings.");
        return;
      case DEFERRED:
        deferredUntil = System.currentTimeMillis() + DEFER_MS;
        log.debug("Bank Ledger deferred the upload: {}", result.getMessage());
        return;
      default:
        log.debug("Bank Ledger upload failed: {}", result.getMessage());
        return;
    }
    if (!warnedStale && !result.getStaleStorages().isEmpty()) {
      warnedStale = true;
      chat("Bank Ledger: some storages haven't been checked in a while - "
          + String.join(", ", result.getStaleStorages().subList(0, Math.min(5, result.getStaleStorages().size())))
          + ". Visit them so Dude, Where's My Stuff can update them.");
    }
  }

  /** Configured, logged in as the chosen account, not waiting out a refusal. Client thread. */
  private boolean readyToUpload() {
    if (rejected || uploading || System.currentTimeMillis() < deferredUntil) {
      return false;
    }
    if (client.getGameState() != GameState.LOGGED_IN) {
      return false;
    }
    if (BankLedgerClient.uploadUrl(config.siteUrl(), config.profileId()) == null
        || !BankLedgerClient.WRITE_KEY.matcher(config.writeKey().trim()).matches()) {
      return false;
    }
    String only = config.accountName().trim();
    if (!only.isEmpty()) {
      String name = localPlayerName();
      return name != null && Text.standardize(name).equals(Text.standardize(only));
    }
    return true;
  }

  private String localPlayerName() {
    Player player = client.getLocalPlayer();
    return player == null ? null : player.getName();
  }

  private void chat(String text) {
    chatMessageManager.queue(
        QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(text).build());
  }
}
