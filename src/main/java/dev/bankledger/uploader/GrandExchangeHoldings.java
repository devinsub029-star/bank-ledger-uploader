package dev.bankledger.uploader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What is locked inside Grand Exchange offers, as a storage to upload alongside DWMS's.
 *
 * <p>Items listed for sale and coins committed to buy offers have left the bank, and DWMS doesn't
 * track them (its "Grand Exchange" storage only reads refunded coins off the collection window), so
 * an upload made with full GE slots understated the bank. This counts only what cannot also be in
 * the bank:
 *
 * <ul>
 *   <li>buy offers, active or cancelled: the coins for the part not yet bought, at the offer price;
 *   <li>sell offers, active or cancelled: the items not yet sold.
 * </ul>
 *
 * <p>The filled part of an offer (items bought, coins received) is left out: it may be waiting in
 * the collection box or already collected into the bank, and the client can't tell which, so
 * counting it could count it twice. It is counted once it is collected.
 *
 * <p>Pure: offers arrive as {@link Offer} values, not RuneLite types, so it is unit tested alone.
 */
final class GrandExchangeHoldings {
  static final String CATEGORY = "ge";
  static final String NAME = "Grand Exchange offers";
  static final int COINS = 995;

  /** One GE slot, copied from RuneLite's GrandExchangeOffer. state is the enum's name. */
  static final class Offer {
    final String state;
    final int itemId;
    final long price;
    final int totalQuantity;
    final int quantitySold;

    Offer(String state, int itemId, long price, int totalQuantity, int quantitySold) {
      this.state = state;
      this.itemId = itemId;
      this.price = price;
      this.totalQuantity = totalQuantity;
      this.quantitySold = quantitySold;
    }
  }

  private GrandExchangeHoldings() {}

  /** {id, quantity} items locked in the offers, merged by id; empty if nothing is. */
  static List<Map<String, Object>> items(List<Offer> offers) {
    TreeMap<Integer, Long> held = new TreeMap<>();
    for (Offer o : offers) {
      if (o == null || o.itemId <= 0 || o.totalQuantity <= 0) {
        continue;
      }
      long unfilled = Math.max(0, (long) o.totalQuantity - o.quantitySold);
      if (unfilled == 0) {
        continue;
      }
      switch (o.state) {
        case "BUYING":
        case "CANCELLED_BUY":
          if (o.price > 0) {
            held.merge(COINS, unfilled * o.price, Long::sum);
          }
          break;
        case "SELLING":
        case "CANCELLED_SELL":
          held.merge(o.itemId, unfilled, Long::sum);
          break;
        default:
          // EMPTY, BOUGHT, SOLD: nothing locked.
          break;
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    held.forEach(
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

  /** DWMS's own GE storage, which this replaces (it overlaps on refunded coins). */
  static boolean isDwmsGrandExchange(Map<?, ?> storage) {
    return "coins".equals(String.valueOf(storage.get("category")))
        && "Grand Exchange".equals(String.valueOf(storage.get("name")));
  }
}
