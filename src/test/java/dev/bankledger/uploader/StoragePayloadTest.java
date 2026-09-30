package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StoragePayloadTest {
  private static Map<String, Object> item(Number id, Number quantity) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", id);
    m.put("quantity", quantity);
    return m;
  }

  private static Map<String, Object> storage(String category, String name, long lastUpdated, List<Map<String, Object>> items) {
    Map<String, Object> m = new HashMap<>();
    m.put("category", category);
    m.put("name", name);
    m.put("lastUpdated", lastUpdated);
    m.put("items", items);
    return m;
  }

  private static Map<String, Object> response(List<Map<String, Object>> storages) {
    Map<String, Object> data = new HashMap<>();
    data.put("source", "Dude, Where's My Stuff?");
    data.put("target", BankLedgerUploaderPlugin.SOURCE);
    data.put("version", 1);
    data.put("storages", storages);
    return data;
  }

  private static List<Map<String, Object>> bank(Object... idQty) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < idQty.length; i += 2) {
      items.add(item((Number) idQty[i], (Number) idQty[i + 1]));
    }
    return items;
  }

  @Test
  public void sameBankSameFingerprintWhateverTheOrder() {
    StoragePayload a = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 10L, 995, 5000L)),
        storage("carryable", "Looting Bag", 1, bank(385, 2L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(
        storage("carryable", "Looting Bag", 1, bank(385, 2L)),
        storage("world", "Bank", 1, bank(995, 5000L, 385, 10L)))));
    assertEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void movingItemsBetweenStoragesIsNotAChange() {
    StoragePayload a = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 12L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 10L)),
        storage("carryable", "Looting Bag", 1, bank(385, 2L)))));
    assertEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void movingCoinsIntoACofferIsAChange() {
    // Same total coins, but fewer of them spendable: the site's on-hand coins must hear of it.
    StoragePayload a = StoragePayload.fromResponse(response(List.of(
        storage("coins", "Bank", 1, bank(995, 2000L)),
        storage("coins", "Managing Miscellania", 1, bank(995, 500L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(
        storage("coins", "Bank", 1, bank(995, 1500L)),
        storage("coins", "Managing Miscellania", 1, bank(995, 1000L)))));
    assertNotEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void coinsMovingAmongSpendableStoragesIsNotAChange() {
    // Withdrawing coins, or putting them in a buy offer, doesn't change what can be spent.
    StoragePayload a = StoragePayload.fromResponse(response(List.of(
        storage("coins", "Bank", 1, bank(995, 2000L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(
        storage("coins", "Bank", 1, bank(995, 1200L)),
        storage("coins", "Inventory", 1, bank(995, 300L)),
        storage("ge", "Grand Exchange offers", 1, bank(995, 500L)))));
    assertEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void quantityChangeChangesTheFingerprint() {
    StoragePayload a = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 1, bank(385, 10L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 1, bank(385, 11L)))));
    assertNotEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void deathAndMinigameStoragesDontCountAsChanges() {
    StoragePayload a = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 1, bank(385, 10L)))));
    StoragePayload b = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 10L)),
        storage("death", "Grave", 1, bank(4151, 1L)),
        storage("minigames", "Guardians of the Rift", 1, bank(556, 400L)))));
    assertEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  public void acceptsIntegerLongAndDoubleNumbers() {
    StoragePayload p = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 10, 995.0, 5000.0)))));
    StoragePayload q = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385L, 10L, 995, 5000L)))));
    assertEquals(p.fingerprint(), q.fingerprint());
  }

  @Test
  public void dropsEmptyAndInvalidItems() {
    StoragePayload p = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 1, bank(385, 0L, -1, 5L, 995, 10L)))));
    StoragePayload q = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 1, bank(995, 10L)))));
    assertEquals(q.fingerprint(), p.fingerprint());
  }

  @Test
  public void requestBodyCarriesVersionStoragesAndOptionalPlayer() {
    StoragePayload p = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 42, bank(385, 1L)))));
    Map<String, Object> body = p.toRequestBody(null, "1.0.0");
    assertEquals(1, body.get("version"));
    assertEquals("bank-ledger-uploader/1.0.0", body.get("client"));
    assertNull(body.get("player"));
    assertEquals("Zezima", p.toRequestBody("Zezima", "1.0.0").get("player"));
    @SuppressWarnings("unchecked")
    Map<String, Object> first = ((List<Map<String, Object>>) body.get("storages")).get(0);
    assertEquals(42L, first.get("lastUpdated"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void refusesOtherVersions() {
    Map<String, Object> data = response(List.of());
    data.put("version", 2);
    StoragePayload.fromResponse(data);
  }

  @Test
  public void emptyWhenDwmsHasNothingYet() {
    assertTrue(StoragePayload.fromResponse(response(List.of())).isEmpty());
    assertFalse(StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 1, bank(1, 1L))))).isEmpty());
  }
}
