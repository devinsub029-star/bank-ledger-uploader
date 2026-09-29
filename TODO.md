# TODO

- [ ] **Upload now, in the plugin's settings.** A way to send the bank
      immediately, without waiting for a bank close, the timer or the
      cooldown. RuneLite's config panel has no plain buttons, so use the usual
      pattern: an "Upload now" checkbox. `onConfigChanged` sees it ticked,
      starts the upload, and unticks it (`configManager.setConfiguration(...,
      false)`). The key must not also reset the cooldown or the last-sent
      state the way other settings changes do. It should:
  - bypass the upload cooldown but still skip an unchanged bank, and report
    "already up to date";
  - confirm in chat either way;
  - check the settings first (profile id, key) and say what's missing.

## Waiting for the next Plugin Hub update

These are committed here but not yet submitted. The Plugin Hub still runs
5014af3, and updates are batched to keep reviews light.

- The upload cooldown ("Minimum time between uploads"), in 2e3f3fb.
- The RuneLite 1.13 build fix (GE offer prices are longs), in 603c98d. The
  live plugin shows "out of date" until this reaches the Plugin Hub.
