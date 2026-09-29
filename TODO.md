# TODO

## Waiting for the next Plugin Hub update

These are committed here but not yet submitted. The Plugin Hub still runs
5014af3, and updates are batched to keep reviews light.

- The upload cooldown ("Minimum time between uploads"), in 2e3f3fb.
- The RuneLite 1.13 build fix (GE offer prices are longs), in 603c98d. The
  live plugin shows "out of date" until this reaches the Plugin Hub.
- **Upload now** in the settings, in 83b962f. RuneLite's config panel has no
  buttons and doesn't redraw when a plugin changes its own setting, so it is a
  checkbox where every click, ticking or unticking, is one upload. It skips
  the cooldown, keeps the last-sent state, and reports in chat, including
  "already up to date" and any missing setting.
- **Send Grand Exchange offers** (off by default), in 83b962f: the GE slots go
  to `POST /api/u/<id>/ge-offers` whenever an offer changes, in 5-second
  batches that leave out slots the site already has. Bank Ledger keeps the
  slots and an event history (owner-only). The Plugin Hub `warning=` should
  mention offers when this is submitted.
- Version 1.1.0.
