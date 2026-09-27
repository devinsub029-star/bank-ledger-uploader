package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GrandExchangeHoldingsTest {
  private static GrandExchangeHoldings.Offer offer(String state, int id, int price, int total, int sold) {
    return new GrandExchangeHoldings.Offer(state, id, price, total, sold);
  }

  private static Map<Integer, Long> asMap(List<Map<String, Object>> items) {
    Map<Integer, Long> m = new HashMap<>();
    for (Map<String, Object> it : items) {
      m.put((Integer) it.get("id"), (Long) it.get("quantity"));
    }
    return m;
  }

  @Test
  public void buyOffersHoldTheCoinsForTheUnfilledPart() {
    Map<Integer, Long> held = asMap(GrandExchangeHoldings.items(List.of(
        offer("BUYING", 4151, 1_500_000, 3, 1),        // 2 whips still to buy
        offer("CANCELLED_BUY", 385, 900, 1000, 400)))); // refund of 600 sharks' coins
    assertEquals(Map.of(995, 2 * 1_500_000L + 600 * 900L), held);
  }

  @Test
  public void sellOffersHoldTheUnsoldItems() {
    Map<Integer, Long> held = asMap(GrandExchangeHoldings.items(List.of(
        offer("SELLING", 385, 900, 1000, 250),
        offer("CANCELLED_SELL", 385, 950, 100, 0),
        offer("SELLING", 4151, 1_600_000, 1, 0))));
    assertEquals(Map.of(385, 850L, 4151, 1L), held);
  }

  @Test
  public void completedAndEmptySlotsHoldNothing() {
    assertTrue(GrandExchangeHoldings.items(List.of(
        offer("BOUGHT", 4151, 1_500_000, 1, 1),
        offer("SOLD", 385, 900, 100, 100),
        offer("EMPTY", 0, 0, 0, 0))).isEmpty());
  }

  @Test
  public void ignoresFullyFilledActiveOffersAndJunk() {
    assertTrue(GrandExchangeHoldings.items(List.of(
        offer("BUYING", 385, 900, 10, 10),
        offer("SELLING", -1, 900, 10, 0),
        offer("BUYING", 385, 0, 10, 0))).isEmpty());
  }

  private static Map<String, Object> response(List<Map<String, Object>> storages) {
    Map<String, Object> data = new HashMap<>();
    data.put("version", 1);
    data.put("storages", storages);
    return data;
  }

  private static Map<String, Object> storage(String category, String name, int id, long qty) {
    Map<String, Object> s = new HashMap<>();
    s.put("category", category);
    s.put("name", name);
    s.put("lastUpdated", 1L);
    List<Map<String, Object>> items = new ArrayList<>();
    items.add(Map.of("id", id, "quantity", qty));
    s.put("items", items);
    return s;
  }

  @Test
  public void replacesDwmsGrandExchangeStorageAndAddsTheOffers() {
    List<Map<String, Object>> dwms = List.of(
        storage("world", "Bank", 385, 100L),
        storage("coins", "Grand Exchange", 995, 540_000L));
    List<Map<String, Object>> ge = GrandExchangeHoldings.items(List.of(offer("CANCELLED_BUY", 385, 900, 600, 0)));

    StoragePayload withGe = StoragePayload.fromResponse(response(dwms), ge, 5L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sent = (List<Map<String, Object>>) withGe.toRequestBody(null, "t").get("storages");
    assertEquals(2, sent.size());
    assertEquals("Bank", sent.get(0).get("name"));
    assertEquals("Grand Exchange offers", sent.get(1).get("name"));
    assertEquals("ge", sent.get(1).get("category"));

    // Same bank, same offers: same fingerprint. The refund is counted once, not twice.
    StoragePayload bankOnly = StoragePayload.fromResponse(response(List.of(
        storage("world", "Bank", 385, 100L), storage("world", "Other", 995, 540_000L))));
    assertEquals(bankOnly.fingerprint(), withGe.fingerprint());
  }

  @Test
  public void listingItemsDoesNotLookLikeLosingThem() {
    // Before: 100 sharks in the bank. After: 60 in the bank, 40 listed for sale.
    StoragePayload before = StoragePayload.fromResponse(response(List.of(storage("world", "Bank", 385, 100L))), List.of(), 1L);
    StoragePayload after = StoragePayload.fromResponse(
        response(List.of(storage("world", "Bank", 385, 60L))),
        GrandExchangeHoldings.items(List.of(offer("SELLING", 385, 900, 40, 0))), 2L);
    assertEquals(before.fingerprint(), after.fingerprint());
  }

  @Test
  public void leavingOffersOutKeepsDwmsStorageAsIs() {
    StoragePayload p = StoragePayload.fromResponse(response(List.of(storage("coins", "Grand Exchange", 995, 10L))), null, 1L);
    StoragePayload q = StoragePayload.fromResponse(response(List.of(storage("coins", "Grand Exchange", 995, 11L))), null, 1L);
    assertNotEquals(p.fingerprint(), q.fingerprint());
  }
}
