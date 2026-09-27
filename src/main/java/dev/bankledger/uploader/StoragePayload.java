package dev.bankledger.uploader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a Dude, Where's My Stuff "storages-response" plugin message into the body Bank Ledger's
 * /storages endpoint takes, and fingerprints the bank so an unchanged one is never re-sent.
 *
 * <p>Pure: no RuneLite types, so it is unit tested on its own.
 */
final class StoragePayload {
  static final int FORMAT_VERSION = 1;

  /**
   * Categories the server leaves out (death storages: items at risk, not held; minigames: point
   * counters that reuse item ids). Left out of the fingerprint too, so a new grave doesn't count as
   * a changed bank.
   */
  static final Set<String> SKIPPED_CATEGORIES = Set.of("death", "minigames");

  private final List<Map<String, Object>> storages;
  private final String fingerprint;

  private StoragePayload(List<Map<String, Object>> storages, String fingerprint) {
    this.storages = storages;
    this.fingerprint = fingerprint;
  }

  /**
   * Reads the "storages" list of a version 1 response. Values arrive as Object (the message map
   * is untyped), and numbers may be Integer, Long or Double depending on how they were boxed.
   *
   * @throws IllegalArgumentException if the response isn't a version 1 storage list
   */
  static StoragePayload fromResponse(Map<String, Object> data) {
    return fromResponse(data, null, 0);
  }

  /**
   * As above, plus what is locked in Grand Exchange offers (see {@link GrandExchangeHoldings}),
   * which replaces DWMS's own "Grand Exchange" coin storage. Null leaves the GE out entirely.
   */
  static StoragePayload fromResponse(
      Map<String, Object> data, List<Map<String, Object>> grandExchangeItems, long now) {
    Object version = data.get("version");
    if (!(version instanceof Number) || ((Number) version).intValue() != FORMAT_VERSION) {
      throw new IllegalArgumentException("Unsupported Dude, Where's My Stuff message version: " + version);
    }
    Object rawStorages = data.get("storages");
    if (!(rawStorages instanceof List)) {
      throw new IllegalArgumentException("No storage list in the response");
    }

    List<Object> all = new ArrayList<>((List<?>) rawStorages);
    if (grandExchangeItems != null) {
      all.removeIf(s -> s instanceof Map && GrandExchangeHoldings.isDwmsGrandExchange((Map<?, ?>) s));
      if (!grandExchangeItems.isEmpty()) {
        all.add(GrandExchangeHoldings.storage(grandExchangeItems, now));
      }
    }

    List<Map<String, Object>> storages = new ArrayList<>();
    TreeMap<Integer, Long> merged = new TreeMap<>();
    for (Object rawStorage : all) {
      if (!(rawStorage instanceof Map)) {
        continue;
      }
      Map<?, ?> storage = (Map<?, ?>) rawStorage;
      String category = String.valueOf(storage.get("category"));
      List<Map<String, Object>> items = new ArrayList<>();
      Object rawItems = storage.get("items");
      if (rawItems instanceof List) {
        for (Object rawItem : (List<?>) rawItems) {
          if (!(rawItem instanceof Map)) {
            continue;
          }
          Object id = ((Map<?, ?>) rawItem).get("id");
          Object quantity = ((Map<?, ?>) rawItem).get("quantity");
          if (!(id instanceof Number) || !(quantity instanceof Number)) {
            continue;
          }
          int itemId = ((Number) id).intValue();
          long qty = ((Number) quantity).longValue();
          if (itemId <= 0 || qty <= 0) {
            continue;
          }
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", itemId);
          item.put("quantity", qty);
          items.add(item);
          if (!SKIPPED_CATEGORIES.contains(category)) {
            merged.merge(itemId, qty, Long::sum);
          }
        }
      }

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("category", category);
      out.put("name", String.valueOf(storage.get("name")));
      Object lastUpdated = storage.get("lastUpdated");
      out.put("lastUpdated", lastUpdated instanceof Number ? ((Number) lastUpdated).longValue() : -1L);
      out.put("items", items);
      storages.add(out);
    }
    return new StoragePayload(Collections.unmodifiableList(storages), fingerprintOf(merged));
  }

  /** The request body. playerName is null unless the user chose to send it. */
  Map<String, Object> toRequestBody(String playerName, String clientVersion) {
    Map<String, Object> body = new HashMap<>();
    body.put("version", FORMAT_VERSION);
    body.put("client", "bank-ledger-uploader/" + clientVersion);
    body.put("storages", storages);
    if (playerName != null && !playerName.isEmpty()) {
      body.put("player", playerName);
    }
    return body;
  }

  /** A hash of the merged bank (id to total quantity, kept categories only). */
  String fingerprint() {
    return fingerprint;
  }

  boolean isEmpty() {
    return storages.isEmpty();
  }

  private static String fingerprintOf(TreeMap<Integer, Long> merged) {
    StringBuilder canonical = new StringBuilder();
    merged.forEach((id, qty) -> canonical.append(id).append(':').append(qty).append(';'));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // SHA-256 is always present
    }
  }
}
