package dev.bankledger.uploader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Value;

/**
 * Which Grand Exchange slots have changed since Bank Ledger last accepted them.
 *
 * <p>The client reports a slot on every change - an offer placed, each partial fill, a cancel, a
 * collect - and re-reports all eight on login and world hop. Slots are queued as they change and
 * sent in one batch a few seconds later; a slot that matches what the site already has is left out,
 * so re-reports cost nothing. A failed batch is queued again unless the slot has changed since.
 *
 * <p>Pure and thread-safe: events arrive on the client thread, replies on OkHttp's.
 */
final class GrandExchangeSlots {
  /** One slot as RuneLite reports it. state is GrandExchangeOfferState's name. */
  @Value
  static class Slot {
    int slot;
    String state;
    int itemId;
    long price;
    int totalQuantity;
    int quantitySold;
    long spent;

    Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("slot", slot);
      m.put("state", state);
      m.put("itemId", itemId);
      m.put("price", price);
      m.put("totalQuantity", totalQuantity);
      m.put("quantitySold", quantitySold);
      m.put("spent", spent);
      return m;
    }
  }

  private final Map<Integer, Slot> pending = new TreeMap<>();
  private final Map<Integer, Slot> sent = new HashMap<>();
  private List<Slot> inFlight;

  /** A slot changed. Returns true if it differs from what the site has, so a send is worth it. */
  synchronized boolean changed(Slot slot) {
    if (slot.equals(sent.get(slot.getSlot()))) {
      pending.remove(slot.getSlot());
      return false;
    }
    pending.put(slot.getSlot(), slot);
    return true;
  }

  /** The slots to send now, or null if nothing is waiting or a batch is already on its way. */
  synchronized List<Slot> takeBatch() {
    if (inFlight != null || pending.isEmpty()) {
      return null;
    }
    inFlight = new ArrayList<>(pending.values());
    pending.clear();
    return inFlight;
  }

  /** The site stored the batch (or already had it). */
  synchronized void accepted() {
    if (inFlight != null) {
      inFlight.forEach(s -> sent.put(s.getSlot(), s));
      inFlight = null;
    }
  }

  /** The batch didn't get there: queue it again, behind anything newer. */
  synchronized void failed() {
    if (inFlight != null) {
      inFlight.forEach(s -> pending.putIfAbsent(s.getSlot(), s));
      inFlight = null;
    }
  }

  synchronized boolean hasPending() {
    return !pending.isEmpty();
  }

  /** A different account, or new settings: forget what the site has. */
  synchronized void reset() {
    pending.clear();
    sent.clear();
    inFlight = null;
  }

  static Map<String, Object> requestBody(List<Slot> slots) {
    List<Map<String, Object>> list = new ArrayList<>();
    slots.forEach(s -> list.add(s.toJson()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", 1);
    body.put("slots", list);
    return body;
  }
}
