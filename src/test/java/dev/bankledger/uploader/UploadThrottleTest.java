package dev.bankledger.uploader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UploadThrottleTest {
  private static final long MIN = 60_000;
  private static final long COOLDOWN = 10 * MIN;

  @Test
  public void firstUploadIsAlwaysAllowed() {
    assertTrue(new UploadThrottle().allows(5, COOLDOWN));
  }

  @Test
  public void changesDuringTheCooldownAreHeldThenSentOnce() {
    UploadThrottle t = new UploadThrottle();
    t.stored(0);
    // Back and forth between the bank and the GE: three changes in a few minutes.
    for (long at : new long[] {1 * MIN, 3 * MIN, 6 * MIN}) {
      assertFalse(t.allows(at, COOLDOWN));
      t.hold();
      assertFalse("not due during the cooldown", t.due(at, COOLDOWN));
    }
    assertTrue("due once the cooldown ends", t.due(10 * MIN, COOLDOWN));
    t.stored(10 * MIN); // the one merged upload
    assertFalse(t.due(11 * MIN, COOLDOWN));
    assertFalse(t.isHolding());
  }

  @Test
  public void nothingIsHeldWithoutAChange() {
    UploadThrottle t = new UploadThrottle();
    t.stored(0);
    assertFalse(t.due(30 * MIN, COOLDOWN));
  }

  @Test
  public void aChangeThatWasUndoneIsNotSent() {
    UploadThrottle t = new UploadThrottle();
    t.stored(0);
    t.hold();
    t.release(); // the bank went back to what was last sent
    assertFalse(t.due(30 * MIN, COOLDOWN));
  }

  @Test
  public void resetClearsTheCooldown() {
    UploadThrottle t = new UploadThrottle();
    t.stored(0);
    t.reset();
    assertTrue(t.allows(1, COOLDOWN));
  }
}
