package dev.bankledger.uploader;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts a development RuneLite client with this plugin loaded: ./gradlew run */
public class BankLedgerUploaderPluginTest {
  public static void main(String[] args) throws Exception {
    ExternalPluginManager.loadBuiltin(BankLedgerUploaderPlugin.class);
    RuneLite.main(args);
  }
}
