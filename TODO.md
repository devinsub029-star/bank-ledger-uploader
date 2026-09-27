# TODO

- [ ] **Upload now button.** A way to send the bank immediately, without
      waiting for a bank close, the timer or the cooldown. It could be a button
      in the plugin's config panel, or a sidebar panel with the last upload's
      time and result. It should:
  - bypass the upload cooldown but still skip an unchanged bank, and report
    "already up to date";
  - confirm in chat either way;
  - check the settings first (profile id, key) and say what's missing.

## Waiting for the next Plugin Hub update

These are committed here but not yet submitted. The Plugin Hub still runs
5014af3, and updates are batched to keep reviews light.

- The upload cooldown ("Minimum time between uploads"), in 2e3f3fb.
