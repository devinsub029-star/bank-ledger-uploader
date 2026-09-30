# TODO

## Live on the Plugin Hub

runelite/plugin-hub#17313, merged 2026-09-29, moved the Plugin Hub from 5014af3 to
d031c5a (1.1.0):

- the RuneLite 1.13 build fix (GE offer prices are longs), in 603c98d;
- the upload cooldown ("Minimum time between uploads"), in 2e3f3fb;
- **Upload now** in the settings, in 83b962f. RuneLite's config panel has no
  buttons and doesn't redraw when a plugin changes its own setting, so it is a
  checkbox where every click, ticking or unticking, is one upload;
- **Send Grand Exchange offers** (off by default), in 83b962f, with the
  `warning=` updated to mention offers.

## Waiting for the next Plugin Hub update

- The player-friendly README description, in 0476914.
- Coins moving into or out of a coffer (Managing Miscellania, NMZ...) count as a
  change and trigger an upload, so Bank Ledger's on-hand coins (`GET coins`,
  read by the GE flipper) stay right. Coins moving among the bank, inventory,
  looting bag and GE offers still don't. Devin approved making the change on
  2026-09-30 but said not to submit it yet.
- Easier manual uploads and fewer reasons to need them (Devin chose these on
  2026-09-30; not submitted):
  - a **Bank Ledger sidebar panel** with an Upload now button, the last
    upload's result and time, and an "Open my dashboard" link;
  - **`::bankledger`** in the chat box uploads right away;
  - an upload on **logout** (the Logout menu option, skipping the cooldown
    quietly) and a few seconds after **collecting from the GE** (a slot that
    empties; through the usual cooldown).
  The settings checkbox still works. Untested in a live client so far: try it
  with `./gradlew run` before submitting.
