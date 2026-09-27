package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.Test;

/**
 * Uploads through the plugin's real payload and client code to a running Bank Ledger. Skipped
 * unless pointed at one - use a local dev server and a throwaway profile:
 *
 * <pre>
 *   BANK_LEDGER_URL=http://127.0.0.1:8787 BANK_LEDGER_PROFILE=abcdefghij BANK_LEDGER_KEY=bl_... ./gradlew test
 * </pre>
 */
public class BankLedgerLiveTest {
  private static Map<String, Object> dwmsResponse(long sharks) {
    List<Map<String, Object>> items = new ArrayList<>();
    items.add(Map.of("id", 385, "quantity", sharks));
    items.add(Map.of("id", 995, "quantity", 250_000L));
    Map<String, Object> bank = new HashMap<>();
    bank.put("category", "world");
    bank.put("name", "Bank");
    bank.put("lastUpdated", System.currentTimeMillis());
    bank.put("items", items);
    Map<String, Object> grave = new HashMap<>();
    grave.put("category", "death");
    grave.put("name", "Grave");
    grave.put("lastUpdated", System.currentTimeMillis());
    grave.put("items", List.of(Map.of("id", 4151, "quantity", 1L)));

    Map<String, Object> data = new HashMap<>();
    data.put("version", 1);
    data.put("target", BankLedgerUploaderPlugin.SOURCE);
    data.put("storages", List.of(bank, grave));
    return data;
  }

  private static UploadResult send(BankLedgerClient client, HttpUrl url, String key, long sharks) throws Exception {
    CompletableFuture<UploadResult> result = new CompletableFuture<>();
    Map<String, Object> body = StoragePayload.fromResponse(dwmsResponse(sharks)).toRequestBody(null, "test");
    client.upload(url, key, body, result::complete);
    return result.get(30, TimeUnit.SECONDS);
  }

  @Test
  public void uploadsThenReportsUnchanged() throws Exception {
    String site = System.getenv("BANK_LEDGER_URL");
    String profile = System.getenv("BANK_LEDGER_PROFILE");
    String key = System.getenv("BANK_LEDGER_KEY");
    assumeTrue("set BANK_LEDGER_URL, BANK_LEDGER_PROFILE and BANK_LEDGER_KEY to run", site != null && profile != null && key != null);

    BankLedgerClient client = new BankLedgerClient(new OkHttpClient(), new Gson());
    HttpUrl url = BankLedgerClient.uploadUrl(site, profile);
    long sharks = 1000 + (System.currentTimeMillis() % 1000);

    assertEquals(UploadResult.Outcome.STORED, send(client, url, key, sharks).getOutcome());
    assertEquals(UploadResult.Outcome.UNCHANGED, send(client, url, key, sharks).getOutcome());
    assertEquals(UploadResult.Outcome.REJECTED, send(client, url, "bl_wrongwrongwrongwrongwrong", sharks + 1).getOutcome());
  }
}
