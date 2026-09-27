package dev.bankledger.uploader;

/**
 * Spaces uploads out. Going back and forth between the bank and the Grand Exchange, or the bank
 * and a trip, changes the bank on nearly every close, and each change was its own upload - twenty
 * in a session that is really one. After a stored upload, further changes wait out a cooldown;
 * the latest bank is then sent once when it ends, so nothing is lost, only merged.
 *
 * <p>Pure and thread-safe (the plugin touches it from the client thread, OkHttp's thread and the
 * scheduler).
 */
final class UploadThrottle {
  private boolean anyStored;
  private long lastStoredAt;
  private boolean held;

  /** Whether an upload may go now. */
  synchronized boolean allows(long now, long cooldownMs) {
    return !anyStored || now - lastStoredAt >= cooldownMs;
  }

  /** A changed bank arrived during the cooldown: send it when the cooldown ends. */
  synchronized void hold() {
    held = true;
  }

  /** The bank is back to what was last sent: nothing is waiting any more. */
  synchronized void release() {
    held = false;
  }

  /** An upload was stored: start the cooldown. */
  synchronized void stored(long now) {
    anyStored = true;
    lastStoredAt = now;
    held = false;
  }

  /** A held change whose cooldown has ended, so it should be fetched and sent now. */
  synchronized boolean due(long now, long cooldownMs) {
    return held && allows(now, cooldownMs);
  }

  synchronized boolean isHolding() {
    return held;
  }

  synchronized void reset() {
    anyStored = false;
    held = false;
  }
}
