package dev.bankledger.uploader;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(BankLedgerUploaderConfig.GROUP)
public interface BankLedgerUploaderConfig extends Config {
  String GROUP = "bankledgeruploader";
  String DEFAULT_SITE = "https://bank-ledger.osrs-bank-tracker.workers.dev";
  String UPLOAD_NOW = "uploadNow";
  String SEND_GE_OFFERS = "sendGeOffers";

  @ConfigSection(
      name = "Profile",
      description = "Which Bank Ledger profile to upload to",
      position = 0)
  String profileSection = "profile";

  @ConfigSection(
      name = "When to upload",
      description = "What triggers an upload",
      position = 1)
  String triggerSection = "triggers";

  @ConfigSection(
      name = "Grand Exchange offers",
      description = "Keep a history of your Grand Exchange offers on Bank Ledger",
      position = 2)
  String geSection = "grandExchange";

  @ConfigSection(
      name = "Privacy",
      description = "What else is sent with your storages",
      position = 3)
  String privacySection = "privacy";

  @ConfigItem(
      keyName = "profileId",
      name = "Profile id",
      description = "The 10 characters after /u/ in your profile's link",
      section = profileSection,
      position = 0)
  default String profileId() {
    return "";
  }

  @ConfigItem(
      keyName = "writeKey",
      name = "Write key",
      description = "Your profile's write key (bl_...). Shown once when the profile was made.",
      secret = true,
      section = profileSection,
      position = 1)
  default String writeKey() {
    return "";
  }

  @ConfigItem(
      keyName = "accountName",
      name = "Only for account",
      description =
          "If set, only this account's storages are uploaded, so logging into an alt can't send"
              + " its bank to this profile. Leave blank to upload any account.",
      section = profileSection,
      position = 2)
  default String accountName() {
    return "";
  }

  @ConfigItem(
      keyName = "siteUrl",
      name = "Site",
      description = "The Bank Ledger site. Only change this if you run your own.",
      section = profileSection,
      position = 3)
  default String siteUrl() {
    return DEFAULT_SITE;
  }

  @ConfigItem(
      keyName = "uploadOnBankClose",
      name = "When the bank closes",
      description = "Check for changes each time you close your bank",
      section = triggerSection,
      position = 0)
  default boolean uploadOnBankClose() {
    return true;
  }

  @Range(min = 0, max = 240)
  @Units(Units.MINUTES)
  @ConfigItem(
      keyName = "intervalMinutes",
      name = "Every",
      description =
          "Also check this often while logged in (0 turns it off). Only a changed bank is sent.",
      section = triggerSection,
      position = 1)
  default int intervalMinutes() {
    return 30;
  }

  @Range(min = 1, max = 120)
  @Units(Units.MINUTES)
  @ConfigItem(
      keyName = "uploadCooldownMinutes",
      name = "Minimum time between uploads",
      description =
          "After an upload, wait this long before the next. Changes made meanwhile - going back"
              + " and forth between the bank and the GE - are sent together when it ends.",
      section = triggerSection,
      position = 2)
  default int uploadCooldownMinutes() {
    return 10;
  }

  @ConfigItem(
      keyName = "includeGrandExchange",
      name = "Count Grand Exchange offers",
      description =
          "Count items listed for sale and coins committed to buy offers, which have left your bank."
              + " Only the unfilled part of each offer is counted, so nothing is counted twice.",
      section = triggerSection,
      position = 3)
  default boolean includeGrandExchange() {
    return true;
  }

  @ConfigItem(
      keyName = "chatMessages",
      name = "Chat message on upload",
      description = "Say in the chat box when a bank was uploaded",
      section = triggerSection,
      position = 4)
  default boolean chatMessages() {
    return false;
  }

  @ConfigItem(
      keyName = UPLOAD_NOW,
      name = "Upload now",
      description =
          "Tick to send your bank right away, without waiting for the cooldown, and see in chat how"
              + " it went. The Bank Ledger sidebar button and typing ::bankledger in chat do the same.",
      section = triggerSection,
      position = 5)
  default boolean uploadNow() {
    return false;
  }

  @ConfigItem(
      keyName = SEND_GE_OFFERS,
      name = "Send Grand Exchange offers",
      description =
          "Send your GE slots (item, price, quantity and progress) whenever an offer changes, so"
              + " Bank Ledger keeps a history of your trades. Only you can see them, with your write"
              + " key.",
      section = geSection,
      position = 0)
  default boolean sendGeOffers() {
    return false;
  }

  @ConfigItem(
      keyName = "sendPlayerName",
      name = "Send display name",
      description =
          "Send your display name with uploads, so a profile set to Wise Old Man with no name can"
              + " fill it in. Off by default; the site never shows it.",
      section = privacySection,
      position = 0)
  default boolean sendPlayerName() {
    return false;
  }
}
