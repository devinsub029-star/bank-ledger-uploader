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

## Ready, not submitted: 1.2.2 (needs Devin's sign-off before a Plugin Hub PR)

Devin, 2026-10-01: "no longer send messages to chat on upload. The side panel can show last uploaded
time / amount. Add in the option to set wait between uploads to 0".
- Routine upload news (uploaded, already up to date, deferred, failed, stale storages) goes to the
  sidebar only; chat keeps only problems that stop uploads (bad write key or settings, DWMS missing).
  The "Chat message on upload" setting is gone.
- The sidebar shows "Last upload: <time>, <gp>", kept per RuneScape profile in the config (survives a
  restart).
- "Minimum time between uploads" accepts 0 (every change is sent right away).

## Live on the Plugin Hub: 1.2.1 (runelite/plugin-hub#17532, merged 2026-10-01; Devin: "submit 1.2.1")

- Removes `::bankledger`. Typing it ran the upload, but the text also reached the game server, and OSRS
  answers unknown `::` commands by making the player say "Hey, everyone, I just tried to do something
  very silly!" in public chat (Devin, 2026-10-01). The sidebar Upload now button replaces it. To submit:
  same steps as 1.2.0 (HANDOFF.md), commit= the 1.2.1 SHA, warning= unchanged.

## Live on the Plugin Hub: 1.2.0 (runelite/plugin-hub#17504, merged and published 2026-10-01 01:21 UTC)

Devin signed off on 2026-09-30 ("Go ahead and pr what's there"). The PR moves commit= from d031c5a to
00c9488 (1.2.0); warning= is unchanged. It contains everything listed below; the build and upload checks passed.

## Was waiting for the next Plugin Hub update (now in 1.2.0)

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
