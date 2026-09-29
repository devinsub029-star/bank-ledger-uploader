# Bank Ledger Uploader

A RuneLite plugin that uploads the storages
[Dude, Where's My Stuff?](https://github.com/Thource/dude-wheres-my-stuff)
(DWMS) tracks to a [Bank Ledger](https://bank-ledger.osrs-bank-tracker.workers.dev)
profile, so your bank's worth is charted without exporting CSVs by hand or
running a Windows scheduled task.

It is a **second, separate plugin**. DWMS is not modified: since July 2026
(PR #435) it answers other plugins' requests for its storage data over
RuneLite's event bus, and this plugin is one such requester. You need both
installed.

## Setup

1. Install **Dude, Where's My Stuff?** and this plugin.
2. Create a profile on Bank Ledger, or use yours. In this plugin's settings,
   enter the **profile id** (the 10 characters after `/u/`) and the
   **write key** (`bl_...`).
3. Optional: set **Only for account** to your main's name so an alt's bank can
   never go to this profile.

Close your bank and the bank is uploaded. It then checks every 30 minutes
(configurable, 0 to turn off) and sends only when something changed. After an
upload it waits at least 10 minutes (configurable) before the next, so going
back and forth between the bank and the GE is one upload, not twenty.

To send your bank straight away, click **Upload now** in the settings. RuneLite's
settings have no buttons, so it is a checkbox: every click, ticking or
unticking, is one upload. It skips the cooldown, and the chat box says how it
went, including "already up to date" when nothing changed.

## How it works

```
 you close the bank / the timer fires
            |
            v
 post PluginMessage  ns="dudewheresmystuff"  name="storages-request"
                     data={ source: "Bank Ledger Uploader" }
            |
            v   (DWMS, on the client thread)
 PluginMessage  name="storages-response"
   data={ target: "Bank Ledger Uploader", version: 1,
          storages: [ { category, name, lastUpdated, items: [ {id, quantity} ] } ] }
            |
            v
 StoragePayload: merge by canonical id, fingerprint (SHA-256 of id:qty)
            |
   same fingerprint as the last accepted upload?  -- yes --> stop (no request)
            |
            no
            v
 POST {site}/api/u/{profile}/storages   Authorization: Bearer {write key}
   { version: 1, client, storages, player? }
            |
            v
 201 stored | 200 unchanged | 401/404 rejected | 429/503 deferred | other failed
```

### What gets uploaded

Exactly what DWMS's plugin API returns, which is the same set of storages its
own **combined** CSV export uses (`StorageManagerManager.getStorages()`):

- carryables (looting bag, rune pouch, herb sack...), STASH units, POH
  storages, world storages (bank, group storage, seed vault, leprechaun...),
  and coin storages other than the bank, inventory and looting bag coins,
  which the world and carryable storages already count;
- only storages that are enabled and withdrawable (an expired deathbank, the
  Forestry shop or Sandstorm don't count);
- item ids are canonical: noted items and placeholders arrive as the base
  item;
- **never** death storages, minigame point counters or sailing storages.
  The site also drops `death` and `minigames` if they ever appear, as its CSV
  path does.

### Grand Exchange offers

Items listed for sale and coins committed to buy offers have left your bank,
and DWMS doesn't track them, so without this a bank uploaded with full GE
slots would look like it lost value. With **Count Grand Exchange offers** on
(the default), the plugin adds what is locked in your offers:

- buy offers, active or cancelled: the coins for the part not yet bought;
- sell offers, active or cancelled: the items not yet sold.

It replaces DWMS's own "Grand Exchange" coin storage, which reads refunds off
the collection window and would overlap. The filled part of an offer (items
bought, coins received) is counted once collected: until then the client
can't tell whether it is still in the collection box or already in the bank,
and guessing could count it twice. Listing items, then, doesn't change your
bank's value; only a sale or purchase does.

One thing to know: DWMS filters this list with its **Item Count Tooltip >
Include ... storages** settings. If you turn one of those off (say, POH
storages), DWMS leaves that category out of its combined export and out of
what this plugin receives. Leave them on for a complete bank.

### Offer history

Separately, and off by default, **Send Grand Exchange offers** sends your GE
slots whenever an offer changes: when you place it, as it fills, when you
cancel it and when you collect it. For each slot that changed it sends the
item, side, price, quantity, how much has filled and the gp involved. Changes
a few seconds apart go together, and a slot the site already has isn't sent
again. Bank Ledger keeps the current slots and a history of what happened to
each offer. Only the profile's owner can read them, with the write key, even
on a public profile. Seasonal and other special worlds are left out.

### Why it doesn't spam the site

- **A cooldown between uploads.** After a stored upload, changes made within
  **Minimum time between uploads** are held, and the latest bank is sent once
  when it ends. A change you undo in the meantime isn't sent at all.
- **Fingerprint first.** The merged bank (id to total quantity) is hashed.
  Moving items between storages, or a new grave, doesn't change it. An
  unchanged bank makes no request at all.
- **Server-side dedupe.** If the fingerprint is new to the plugin (after a
  restart) but the bank matches the latest snapshot, the site answers
  `unchanged` without writing anything or counting an upload.
- **Backs off.** A rate limit or read-only reply pauses uploads for 15
  minutes. A rejected key stops uploads until the settings change.
- **One at a time**, never while logged out.

### Staleness

Every storage carries DWMS's `lastUpdated`. The site counts storages DWMS
hasn't seen in 30 days (or ever) but lists them back as `staleStorages`, and
the plugin says so in chat once a session, so you know to visit them.

## Privacy

- Nothing is sent until a profile id and write key are set.
- What is sent: item ids and quantities per storage, storage names and
  categories, when DWMS last saw each storage, and the plugin version.
- Your Grand Exchange offers only if you turn on **Send Grand Exchange
  offers** (see [Offer history](#offer-history)).
- Your display name is sent only if you turn on **Send display name**, which
  lets a profile set to "Wise Old Man, name blank" fill in the name. The site
  never shows it.
- The write key is a secret config value. It goes only to the site in
  **Site** (https only, apart from a local dev server).
- The Plugin Hub shows a warning before install that this plugin sends data
  to a third-party server.
