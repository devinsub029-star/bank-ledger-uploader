package dev.bankledger.uploader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * What is loaded inside charged weapons, as a storage to upload alongside DWMS's.
 *
 * <p>Runes put into Tumeken's shadow and scales put into a toxic blowpipe leave the bank, and DWMS
 * doesn't track charges, so a charged weapon looked like the supplies had been used up and the bank
 * was worth less than it is. The game keeps each weapon's charges in a varbit; this turns them back
 * into the items they hold:
 *
 * <ul>
 *   <li>Tumeken's shadow: 2 soul runes and 5 chaos runes a charge;
 *   <li>toxic blowpipe: its scales (darts aren't in a varbit, so they aren't counted);
 *   <li>Sanguinesti staff: 3 blood runes a charge.
 * </ul>
 *
 * <p>Charges only count while the charged weapon itself is in a tracked storage, so a stale varbit
 * for a weapon that was sold or emptied adds nothing.
 *
 * <p>Pure: varbit values arrive as a map, so it is unit tested alone.
 */
final class WeaponCharges {
  static final String CATEGORY = "charges";
  static final String NAME = "Weapon charges";

  static final int SOUL_RUNE = 566;
  static final int CHAOS_RUNE = 562;
  static final int BLOOD_RUNE = 565;
  static final int ZULRAHS_SCALES = 12934;

  /** A charged weapon: the ids it has when charged, its varbit, and what one charge holds. */
  static final class Weapon {
    final String name;
    final Set<Integer> chargedIds;
    final int varbit;
    /** {item id, quantity per charge} pairs. */
    final int[][] perCharge;

    Weapon(String name, Set<Integer> chargedIds, int varbit, int[][] perCharge) {
      this.name = name;
      this.chargedIds = chargedIds;
      this.varbit = varbit;
      this.perCharge = perCharge;
    }
  }

  static final List<Weapon> WEAPONS =
      List.of(
          new Weapon(
              "Tumeken's shadow",
              Set.of(ItemID.TUMEKENS_SHADOW),
              VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY,
              new int[][] {{SOUL_RUNE, 2}, {CHAOS_RUNE, 5}}),
          new Weapon(
              "Toxic blowpipe",
              Set.of(ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT),
              VarbitID.CHARGES_TOXIC_BLOWPIPE_QUANTITY,
              new int[][] {{ZULRAHS_SCALES, 1}}),
          new Weapon(
              "Sanguinesti staff",
              Set.of(ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_OR),
              VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY,
              new int[][] {{BLOOD_RUNE, 3}}));

  private WeaponCharges() {}

  /**
   * {id, quantity} items loaded in the weapons, merged by id; empty if none.
   *
   * @param charges varbit id to its value
   * @param held item ids in the player's tracked storages
   */
  static List<Map<String, Object>> items(Map<Integer, Integer> charges, Set<Integer> held) {
    TreeMap<Integer, Long> loaded = new TreeMap<>();
    for (Weapon w : WEAPONS) {
      Integer value = charges.get(w.varbit);
      if (value == null || value <= 0 || w.chargedIds.stream().noneMatch(held::contains)) {
        continue;
      }
      for (int[] part : w.perCharge) {
        loaded.merge(part[0], (long) value * part[1], Long::sum);
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    loaded.forEach(
        (id, qty) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", id);
          item.put("quantity", qty);
          items.add(item);
        });
    return items;
  }

  /** The storage entry in the shape DWMS's response uses. */
  static Map<String, Object> storage(List<Map<String, Object>> items, long now) {
    Map<String, Object> storage = new LinkedHashMap<>();
    storage.put("category", CATEGORY);
    storage.put("name", NAME);
    storage.put("lastUpdated", now);
    storage.put("items", items);
    return storage;
  }
}
