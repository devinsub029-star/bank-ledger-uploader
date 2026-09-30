package dev.bankledger.uploader;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
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
 *
 * <p>If the owner turns it on, it also sends the Grand Exchange slots whenever an offer changes, for
 * the site's offer history.
 */
@Slf4j
@PluginDescriptor(
    name = "Bank Ledger Uploader",
    description = "Uploads your Dude, Where's My Stuff storages to Bank Ledger",
    tags = {"bank", "value", "worth", "tracker", "dwms", "export", "grand exchange"})
public class BankLedgerUploaderPlugin extends Plugin {
  static final String DWMS_NAMESPACE = "dudewheresmystuff";
  static final String REQUEST = "storages-request";
  static final String RESPONSE = "storages-response";
  static final String SOURCE = "Bank Ledger Uploader";
  static final String VERSION = "1.1.0";

  /** How long DWMS gets to answer before we decide it isn't there. */
  private static final long RESPONSE_TIMEOUT_MS = 10_000;
  /** After a rate limit or read-only reply, wait this long before trying again. */
  private static final long DEFER_MS = 15 * 60_000;
  /** GE changes come in bursts (a fill, then the next); they are sent together after this. */
  private static final long GE_BATCH_MS = 5_000;
  /** After collecting from the GE, give DWMS a moment to see the items land before asking. */
  private static final long COLLECT_DELAY_MS = 3_000;
  /** Typed in the chat box as "::bankledger" to upload right away. */
  static final String CHAT_COMMAND = "bankledger";

  @Inject private Client client;
  @Inject private ClientThread clientThread;
  @Inject private EventBus eventBus;
  @Inject private ConfigManager configManager;
  @Inject private ChatMessageManager chatMessageManager;
  @Inject private BankLedgerUploaderConfig config;
  @Inject private OkHttpClient okHttpClient;
  @Inject private Gson gson;
  @Inject private ScheduledExecutorService executor;
  @Inject private ClientToolbar clientToolbar;

  private BankLedgerClient bankLedger;
  private BankLedgerPanel panel;
  private NavigationButton navButton;

  /** Fingerprint of the last bank the site accepted, per RuneScape profile. */
  private final Map<String, String> lastSent = new HashMap<>();

  private volatile long lastRequestAt;
  private volatile long pendingSince;
  private volatile long deferredUntil;
  private volatile boolean uploading;
  private volatile boolean rejected;
  /** The pending storages request came from "Upload now": skip the cooldown and report back. */
  private volatile boolean manual;
  /** The pending request is a logout: skip the cooldown, but quietly. */
  private volatile boolean forced;
  /** Each GE slot's last state, to tell a collected offer (a slot that just emptied). */
  private final GrandExchangeOfferState[] geSeen = new GrandExchangeOfferState[8];
  private volatile boolean collectScheduled;
  private final UploadThrottle throttle = new UploadThrottle();
  private boolean warnedMissingDwms;
  private boolean warnedStale;

  private final GrandExchangeSlots geSlots = new GrandExchangeSlots();
  private volatile boolean geBatchScheduled;
  private volatile long geDeferredUntil;
  private String geProfile;

  @Provides
  BankLedgerUploaderConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(BankLedgerUploaderConfig.class);
  }

  @Override
  protected void startUp() {
    bankLedger = new BankLedgerClient(okHttpClient, gson);
    panel = new BankLedgerPanel(() -> clientThread.invokeLater(this::uploadNow), this::openDashboard);
    navButton =
        NavigationButton.builder()
            .tooltip("Bank Ledger")
            .icon(BankLedgerPanel.icon())
            .priority(8)
            .panel(panel)
            .build();
    clientToolbar.addNavigation(navButton);
    rejected = false;
    warnedMissingDwms = false;
    if (config.sendGeOffers()) {
      clientThread.invokeLater(this::queueAllGeSlots);
    }
  }

  @Override
  protected void shutDown() {
    clientToolbar.removeNavigation(navButton);
    navButton = null;
    panel = null;
    lastSent.clear();
    pendingSince = 0;
    manual = false;
    geSlots.reset();
    geProfile = null;
  }

  @Subscribe
  public void onConfigChanged(ConfigChanged event) {
    if (!BankLedgerUploaderConfig.GROUP.equals(event.getGroup())) {
      return;
    }
    if (BankLedgerUploaderConfig.UPLOAD_NOW.equals(event.getKey())) {
      // A button, in a panel that has none: every click, ticking or unticking, is one upload.
      // It isn't a settings change, so the cooldown and what was last sent stay as they are.
      clientThread.invokeLater(this::uploadNow);
      return;
    }
    // New settings deserve a fresh try, and a first upload.
    rejected = false;
    deferredUntil = 0;
    geDeferredUntil = 0;
    lastSent.clear();
    throttle.reset();
    geSlots.reset();
    if (config.sendGeOffers()) {
      clientThread.invokeLater(this::queueAllGeSlots);
    }
  }

  @Subscribe
  public void onGameStateChanged(GameStateChanged event) {
    if (event.getGameState() == GameState.LOGIN_SCREEN) {
      warnedStale = false;
      pendingSince = 0;
    }
  }

  /** "::bankledger" in the chat box: the same as the sidebar's Upload now. Client thread. */
  @Subscribe
  public void onCommandExecuted(CommandExecuted event) {
    if (CHAT_COMMAND.equalsIgnoreCase(event.getCommand())) {
      uploadNow();
    }
  }

  /** Logging out: send any change now rather than leave it until the next session. */
  @Subscribe
  public void onMenuOptionClicked(MenuOptionClicked event) {
    if ("Logout".equalsIgnoreCase(Text.removeTags(event.getMenuOption())) && readyToUpload()) {
      forced = true;
      requestStorages();
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
      if (manual || !warnedMissingDwms) {
        manual = false;
        warnedMissingDwms = true;
        chat("Bank Ledger Uploader needs the Dude, Where's My Stuff? plugin installed and enabled.");
      }
    }
    int interval = config.intervalMinutes();
    if (throttle.due(now, cooldownMs())) {
      // A change was held back during the cooldown: fetch the latest bank and send it.
      clientThread.invokeLater(this::requestStorages);
    } else if (interval > 0 && now - lastRequestAt >= interval * 60_000L) {
      clientThread.invokeLater(this::requestStorages);
    }
    if (geSlots.hasPending()) {
      // A batch that failed, or arrived before the player had loaded.
      clientThread.invokeLater(this::sendGeSlots);
    }
  }

  /** "Upload now" in the settings. Client thread. */
  private void uploadNow() {
    String problem = settingsProblem();
    if (problem != null) {
      chat("Bank Ledger: " + problem);
      return;
    }
    if (client.getGameState() != GameState.LOGGED_IN) {
      chat("Bank Ledger: log in to upload your bank.");
      return;
    }
    if (!isChosenAccount()) {
      chat("Bank Ledger: this isn't the account set in \"Only for account\", so nothing was sent.");
      return;
    }
    if (uploading || manual) {
      chat("Bank Ledger: an upload is already on its way.");
      return;
    }
    // Asked for by hand: a refusal or a rate limit gets one more try.
    rejected = false;
    deferredUntil = 0;
    manual = true;
    requestStorages();
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
    boolean byHand = manual;
    boolean skipCooldown = byHand || forced;
    manual = false;
    forced = false;
    if (!readyToUpload()) {
      return;
    }

    StoragePayload payload;
    try {
      payload =
          StoragePayload.fromResponse(
              message.getData(),
              config.includeGrandExchange() ? grandExchangeItems() : null,
              System.currentTimeMillis());
    } catch (IllegalArgumentException e) {
      log.warn("Unexpected Dude, Where's My Stuff response: {}", e.getMessage());
      return;
    }
    if (payload.isEmpty()) {
      // DWMS hasn't loaded this account's data yet
      if (byHand) {
        chat("Bank Ledger: Dude, Where's My Stuff hasn't recorded your bank yet - open your bank once.");
      }
      return;
    }

    String profile = Objects.toString(configManager.getRSProfileKey(), "");
    String fingerprint = payload.fingerprint();
    if (fingerprint.equals(lastSent.get(profile))) {
      throttle.release(); // nothing changed since the last upload
      if (byHand) {
        chat("Bank Ledger: already up to date.");
      }
      return;
    }
    if (!skipCooldown && !throttle.allows(System.currentTimeMillis(), cooldownMs())) {
      throttle.hold(); // sent when the cooldown ends
      return;
    }

    HttpUrl url = BankLedgerClient.uploadUrl(config.siteUrl(), config.profileId());
    if (url == null) {
      return;
    }
    String player = config.sendPlayerName() ? localPlayerName() : null;
    uploading = true;
    bankLedger.upload(url, config.writeKey(), payload.toRequestBody(player, VERSION),
        result -> onUploaded(result, profile, fingerprint, byHand));
  }

  /** OkHttp thread. */
  private void onUploaded(UploadResult result, String profile, String fingerprint, boolean byHand) {
    uploading = false;
    switch (result.getOutcome()) {
      case STORED:
        lastSent.put(profile, fingerprint);
        throttle.stored(System.currentTimeMillis());
        String stored = String.format("Bank uploaded to Bank Ledger (%,d gp).", result.getActualWorth());
        if (byHand || config.chatMessages()) {
          chat(stored);
        } else {
          status(stored);
        }
        break;
      case UNCHANGED:
        lastSent.put(profile, fingerprint);
        throttle.release();
        if (byHand) {
          chat("Bank Ledger: already up to date.");
        }
        break;
      case REJECTED:
        rejected = true;
        chat(result.getMessage() + " - check the Bank Ledger Uploader settings.");
        return;
      case DEFERRED:
        deferredUntil = System.currentTimeMillis() + DEFER_MS;
        log.debug("Bank Ledger deferred the upload: {}", result.getMessage());
        if (byHand) {
          chat("Bank Ledger: " + result.getMessage());
        }
        return;
      default:
        log.debug("Bank Ledger upload failed: {}", result.getMessage());
        if (byHand) {
          chat("Bank Ledger: the upload failed - " + result.getMessage() + ".");
        }
        return;
    }
    if (!warnedStale && !result.getStaleStorages().isEmpty()) {
      warnedStale = true;
      chat("Bank Ledger: some storages haven't been checked in a while - "
          + String.join(", ", result.getStaleStorages().subList(0, Math.min(5, result.getStaleStorages().size())))
          + ". Visit them so Dude, Where's My Stuff can update them.");
    }
  }

  @Subscribe
  public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
    GrandExchangeOffer offer = event.getOffer();
    if (offer == null || offer.getState() == null) {
      return;
    }
    noteCollected(event.getSlot(), offer.getState());
    if (!config.sendGeOffers()) {
      return;
    }
    // The client empties every slot on the login screen and while hopping, then reports them
    // again; those empties aren't real.
    if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN) {
      return;
    }
    queueGeSlot(event.getSlot(), offer);
  }

  /**
   * A slot that just emptied was collected: coins or items moved into the bank or inventory, so
   * upload once DWMS has seen them (through the usual cooldown). Client thread.
   */
  private void noteCollected(int slot, GrandExchangeOfferState state) {
    if (slot < 0 || slot >= geSeen.length) {
      return;
    }
    if (client.getGameState() != GameState.LOGGED_IN) {
      geSeen[slot] = null; // login and hopping report every slot again; those aren't collections
      return;
    }
    GrandExchangeOfferState before = geSeen[slot];
    geSeen[slot] = state;
    if (state == GrandExchangeOfferState.EMPTY
        && before != null
        && before != GrandExchangeOfferState.EMPTY
        && !collectScheduled) {
      collectScheduled = true;
      executor.schedule(
          () -> {
            collectScheduled = false;
            clientThread.invokeLater(this::requestStorages);
          },
          COLLECT_DELAY_MS,
          TimeUnit.MILLISECONDS);
    }
  }

  /** Client thread. */
  private void queueGeSlot(int slot, GrandExchangeOffer offer) {
    // Seasonal and other special worlds have their own GE; their trades aren't this bank's.
    if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) {
      return;
    }
    String profile = Objects.toString(configManager.getRSProfileKey(), "");
    if (!profile.equals(geProfile)) {
      geSlots.reset(); // another account: the site's copy of its slots is unknown here
      geProfile = profile;
    }
    boolean empty = offer.getState() == GrandExchangeOfferState.EMPTY;
    GrandExchangeSlots.Slot s =
        new GrandExchangeSlots.Slot(
            slot,
            offer.getState().name(),
            empty ? 0 : offer.getItemId(),
            empty ? 0 : offer.getPrice(),
            empty ? 0 : offer.getTotalQuantity(),
            empty ? 0 : offer.getQuantitySold(),
            empty ? 0 : offer.getSpent());
    if (geSlots.changed(s) && !geBatchScheduled) {
      geBatchScheduled = true;
      executor.schedule(
          () -> {
            geBatchScheduled = false;
            clientThread.invokeLater(this::sendGeSlots);
          },
          GE_BATCH_MS,
          TimeUnit.MILLISECONDS);
    }
  }

  /** All eight slots, when the setting is turned on while logged in. Client thread. */
  private void queueAllGeSlots() {
    if (!config.sendGeOffers() || client.getGameState() != GameState.LOGGED_IN) {
      return;
    }
    GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
    if (offers == null) {
      return;
    }
    for (int i = 0; i < offers.length; i++) {
      if (offers[i] != null && offers[i].getState() != null) {
        queueGeSlot(i, offers[i]);
      }
    }
  }

  /** Client thread. */
  private void sendGeSlots() {
    if (!config.sendGeOffers()
        || rejected
        || System.currentTimeMillis() < geDeferredUntil
        || settingsProblem() != null
        || client.getGameState() != GameState.LOGGED_IN
        || !isChosenAccount()) {
      return;
    }
    HttpUrl url = BankLedgerClient.geOffersUrl(config.siteUrl(), config.profileId());
    List<GrandExchangeSlots.Slot> batch = geSlots.takeBatch();
    if (url == null || batch == null) {
      return;
    }
    bankLedger.upload(url, config.writeKey(), GrandExchangeSlots.requestBody(batch), this::onGeSent);
  }

  /** OkHttp thread. */
  private void onGeSent(UploadResult result) {
    switch (result.getOutcome()) {
      case STORED:
      case UNCHANGED:
        geSlots.accepted();
        break;
      case REJECTED:
        geSlots.failed();
        rejected = true;
        chat(result.getMessage() + " - check the Bank Ledger Uploader settings.");
        break;
      case DEFERRED:
        geSlots.failed();
        geDeferredUntil = System.currentTimeMillis() + DEFER_MS;
        log.debug("Bank Ledger deferred the GE offers: {}", result.getMessage());
        break;
      default:
        geSlots.failed(); // tried again within a minute
        log.debug("Sending GE offers failed: {}", result.getMessage());
        break;
    }
  }

  /** What's wrong with the profile settings, or null if they can be used. */
  private String settingsProblem() {
    if (BankLedgerClient.uploadUrl(config.siteUrl(), config.profileId()) == null) {
      return config.profileId().trim().isEmpty()
          ? "set your Profile id in the Bank Ledger Uploader settings."
          : "the Profile id or Site in the Bank Ledger Uploader settings isn't valid.";
    }
    if (!BankLedgerClient.WRITE_KEY.matcher(config.writeKey().trim()).matches()) {
      return config.writeKey().trim().isEmpty()
          ? "set your Write key in the Bank Ledger Uploader settings."
          : "the Write key in the Bank Ledger Uploader settings doesn't look right (it starts bl_).";
    }
    return null;
  }

  /** Whether "Only for account", if set, is the one logged in. Client thread. */
  private boolean isChosenAccount() {
    String only = config.accountName().trim();
    if (only.isEmpty()) {
      return true;
    }
    String name = localPlayerName();
    return name != null && Text.standardize(name).equals(Text.standardize(only));
  }

  /** Configured, logged in as the chosen account, not waiting out a refusal. Client thread. */
  private boolean readyToUpload() {
    if (rejected || uploading || System.currentTimeMillis() < deferredUntil) {
      return false;
    }
    return client.getGameState() == GameState.LOGGED_IN && settingsProblem() == null && isChosenAccount();
  }

  /** What is locked in the player's GE offers. Client thread. */
  private List<Map<String, Object>> grandExchangeItems() {
    List<GrandExchangeHoldings.Offer> offers = new ArrayList<>();
    GrandExchangeOffer[] slots = client.getGrandExchangeOffers();
    if (slots != null) {
      for (GrandExchangeOffer o : slots) {
        if (o != null && o.getState() != null) {
          offers.add(
              new GrandExchangeHoldings.Offer(
                  o.getState().name(), o.getItemId(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold()));
        }
      }
    }
    return GrandExchangeHoldings.items(offers);
  }

  private long cooldownMs() {
    return Math.max(1, config.uploadCooldownMinutes()) * 60_000L;
  }

  private String localPlayerName() {
    Player player = client.getLocalPlayer();
    return player == null ? null : player.getName();
  }

  /** The sidebar's "Open my dashboard". Swing thread. */
  private void openDashboard() {
    HttpUrl url = BankLedgerClient.dashboardUrl(config.siteUrl(), config.profileId());
    if (url == null) {
      status("Set your Profile id in the Bank Ledger Uploader settings first.");
      return;
    }
    LinkBrowser.browse(url.toString());
  }

  /** Says it in chat, and shows it in the sidebar too. */
  private void chat(String text) {
    chatMessageManager.queue(
        QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(text).build());
    status(text);
  }

  /** The sidebar's status line, with the time it happened. */
  private void status(String text) {
    BankLedgerPanel p = panel;
    if (p != null) {
      p.setStatus(java.time.LocalTime.now().withNano(0) + " - " + text);
    }
  }
}
