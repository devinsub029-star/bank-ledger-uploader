package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class WeaponChargesTest {
  private static Map<Integer, Long> asMap(List<Map<String, Object>> items) {
    Map<Integer, Long> m = new HashMap<>();
    for (Map<String, Object> it : items) {
      m.put((Integer) it.get("id"), (Long) it.get("quantity"));
    }
    return m;
  }

  @Test
  public void shadowChargesAreSoulAndChaosRunes() {
    Map<Integer, Long> loaded = asMap(WeaponCharges.items(
        Map.of(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, 12_000),
        Set.of(ItemID.TUMEKENS_SHADOW)));
    assertEquals(Map.of(WeaponCharges.SOUL_RUNE, 24_000L, WeaponCharges.CHAOS_RUNE, 60_000L), loaded);
  }

  @Test
  public void blowpipeAndShadowMergeWithTheSang() {
    Map<Integer, Long> loaded = asMap(WeaponCharges.items(
        Map.of(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, 100,
            VarbitID.CHARGES_TOXIC_BLOWPIPE_QUANTITY, 15_000,
            VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY, 1_000),
        Set.of(ItemID.TUMEKENS_SHADOW, ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.SANGUINESTI_STAFF)));
    assertEquals(Map.of(WeaponCharges.SOUL_RUNE, 200L, WeaponCharges.CHAOS_RUNE, 500L,
        WeaponCharges.ZULRAHS_SCALES, 15_000L, WeaponCharges.BLOOD_RUNE, 3_000L), loaded);
  }

  @Test
  public void chargesWithoutTheWeaponCountForNothing() {
    // Sold, uncharged or not tracked: a leftover varbit adds nothing.
    assertTrue(WeaponCharges.items(
        Map.of(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, 5_000),
        Set.of(ItemID.TUMEKENS_SHADOW_UNCHARGED)).isEmpty());
    assertTrue(WeaponCharges.items(
        Map.of(VarbitID.CHARGES_TOXIC_BLOWPIPE_QUANTITY, 0),
        Set.of(ItemID.TOXIC_BLOWPIPE_LOADED)).isEmpty());
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

  private static Map<String, Object> response(List<Map<String, Object>> storages) {
    Map<String, Object> data = new HashMap<>();
    data.put("version", 1);
    data.put("storages", storages);
    return data;
  }

  @Test
  public void chargingTheShadowDoesNotLookLikeLosingTheRunes() {
    // Before: 10,000 soul runes in the bank. After: 8,000 there, 1,000 charges in the worn shadow.
    StoragePayload before = StoragePayload.fromResponse(
        response(List.of(storage("world", "Bank", WeaponCharges.SOUL_RUNE, 10_000L),
            storage("carryable", "Equipment", ItemID.TUMEKENS_SHADOW, 1L))),
        null, Map.of(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, 0), 1L);
    StoragePayload after = StoragePayload.fromResponse(
        response(List.of(storage("world", "Bank", WeaponCharges.SOUL_RUNE, 8_000L),
            storage("carryable", "Equipment", ItemID.TUMEKENS_SHADOW, 1L),
            storage("world", "Bank", WeaponCharges.CHAOS_RUNE, 0L))),
        null, Map.of(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, 1_000), 2L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sent = (List<Map<String, Object>>) after.toRequestBody(null, "t").get("storages");
    Map<String, Object> charges = sent.get(sent.size() - 1);
    assertEquals(WeaponCharges.NAME, charges.get("name"));
    assertEquals(WeaponCharges.CATEGORY, charges.get("category"));
    // The soul runes are all still there; the chaos runes are new, so the bank did change.
    assertNotEquals(before.fingerprint(), after.fingerprint());
  }

  @Test
  public void leavingChargesOutAddsNoStorage() {
    StoragePayload p = StoragePayload.fromResponse(
        response(List.of(storage("carryable", "Equipment", ItemID.TUMEKENS_SHADOW, 1L))), null, null, 1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sent = (List<Map<String, Object>>) p.toRequestBody(null, "t").get("storages");
    assertEquals(1, sent.size());
  }
}
