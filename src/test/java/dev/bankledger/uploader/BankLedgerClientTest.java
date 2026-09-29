package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class BankLedgerClientTest {
  private final BankLedgerClient client = new BankLedgerClient(new OkHttpClient(), new Gson());

  @Test
  public void buildsTheUploadUrl() {
    assertEquals("https://bank-ledger.osrs-bank-tracker.workers.dev/api/u/abcde12345/storages",
        BankLedgerClient.uploadUrl(BankLedgerUploaderConfig.DEFAULT_SITE, "abcde12345").toString());
    assertEquals("https://example.com/api/u/abcdefghij/storages",
        BankLedgerClient.uploadUrl("https://example.com/some/path/", " abcdefghij ").toString());
  }

  @Test
  public void buildsTheGrandExchangeUrlOnTheSameTerms() {
    assertEquals("https://example.com/api/u/abcdefghij/ge-offers",
        BankLedgerClient.geOffersUrl("https://example.com", "abcdefghij").toString());
    assertNull(BankLedgerClient.geOffersUrl("http://example.com", "abcdefghij"));
  }

  @Test
  public void refusesBadIdsAndPlainHttp() {
    assertNull(BankLedgerClient.uploadUrl(BankLedgerUploaderConfig.DEFAULT_SITE, "short"));
    assertNull(BankLedgerClient.uploadUrl(BankLedgerUploaderConfig.DEFAULT_SITE, "ABCDEFGHIJ"));
    assertNull(BankLedgerClient.uploadUrl("http://example.com", "abcdefghij"));
    assertNull(BankLedgerClient.uploadUrl("not a url", "abcdefghij"));
  }

  @Test
  public void allowsALocalDevServerOverHttp() {
    assertEquals("http://127.0.0.1:8787/api/u/abcdefghij/storages",
        BankLedgerClient.uploadUrl("http://127.0.0.1:8787", "abcdefghij").toString());
  }

  @Test
  public void interpretsTheSitesReplies() {
    UploadResult stored = client.interpret(201, "{\"snapshotId\":3,\"actualWorth\":414000000,\"staleStorages\":[\"Seed Vault\"]}");
    assertEquals(UploadResult.Outcome.STORED, stored.getOutcome());
    assertEquals(414000000L, stored.getActualWorth());
    assertEquals(List.of("Seed Vault"), stored.getStaleStorages());

    assertEquals(UploadResult.Outcome.UNCHANGED, client.interpret(200, "{\"unchanged\":true,\"staleStorages\":[]}").getOutcome());
    assertEquals(UploadResult.Outcome.REJECTED, client.interpret(401, "{\"error\":\"This needs the profile's write key\"}").getOutcome());
    assertEquals(UploadResult.Outcome.REJECTED, client.interpret(404, "{\"error\":\"No such profile\"}").getOutcome());
    assertEquals(UploadResult.Outcome.DEFERRED, client.interpret(429, "{\"error\":\"slow down\"}").getOutcome());
    assertEquals(UploadResult.Outcome.DEFERRED, client.interpret(503, "{\"error\":\"read-only\"}").getOutcome());
    assertEquals(UploadResult.Outcome.FAILED, client.interpret(500, "not json").getOutcome());
  }
}
