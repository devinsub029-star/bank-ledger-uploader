package dev.bankledger.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GrandExchangeSlotsTest {
  private static GrandExchangeSlots.Slot buying(int slot, int sold) {
    return new GrandExchangeSlots.Slot(slot, "BUYING", 385, 900, 1000, sold, sold * 900L);
  }

  @Test
  public void batchesChangesAndSkipsWhatTheSiteHas() {
    GrandExchangeSlots slots = new GrandExchangeSlots();
    assertTrue(slots.changed(buying(0, 0)));
    assertTrue(slots.changed(buying(0, 100))); // the newer one replaces it
    assertTrue(slots.changed(buying(3, 0)));
    List<GrandExchangeSlots.Slot> batch = slots.takeBatch();
    assertEquals(List.of(buying(0, 100), buying(3, 0)), batch);
    slots.accepted();

    // A login re-reports every slot: nothing new to send.
    assertFalse(slots.changed(buying(0, 100)));
    assertFalse(slots.changed(buying(3, 0)));
    assertNull(slots.takeBatch());
  }

  @Test
  public void oneBatchAtATime() {
    GrandExchangeSlots slots = new GrandExchangeSlots();
    slots.changed(buying(0, 0));
    slots.takeBatch();
    slots.changed(buying(1, 0));
    assertNull(slots.takeBatch());
    slots.accepted();
    assertEquals(List.of(buying(1, 0)), slots.takeBatch());
  }

  @Test
  public void aFailedBatchIsRetriedUnlessTheSlotMovedOn() {
    GrandExchangeSlots slots = new GrandExchangeSlots();
    slots.changed(buying(0, 0));
    slots.changed(buying(1, 0));
    slots.takeBatch();
    slots.changed(buying(1, 500)); // newer than what was in flight
    slots.failed();
    assertTrue(slots.hasPending());
    assertEquals(List.of(buying(0, 0), buying(1, 500)), slots.takeBatch());
  }

  @Test
  public void resetForgetsEverything() {
    GrandExchangeSlots slots = new GrandExchangeSlots();
    slots.changed(buying(0, 0));
    slots.takeBatch();
    slots.accepted();
    slots.reset();
    assertTrue(slots.changed(buying(0, 0)));
  }

  @Test
  public void buildsTheRequestBody() {
    Map<String, Object> body = GrandExchangeSlots.requestBody(List.of(
        new GrandExchangeSlots.Slot(2, "SELLING", 4151, 3_000_000_000L, 1, 0, 0)));
    assertEquals(1, body.get("version"));
    Map<?, ?> slot = (Map<?, ?>) ((List<?>) body.get("slots")).get(0);
    assertEquals(2, slot.get("slot"));
    assertEquals("SELLING", slot.get("state"));
    assertEquals(3_000_000_000L, slot.get("price"));
  }
}
