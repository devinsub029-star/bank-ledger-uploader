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
(configurable, 0 to turn off) and sends only when something changed.

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

One thing to know: DWMS filters this list with its **Item Count Tooltip >
Include ... storages** settings. If you turn one of those off (say, POH
storages), DWMS leaves that category out of its combined export and out of
what this plugin receives. Leave them on for a complete bank.

### Why it doesn't spam the site

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
- Your display name is sent only if you turn on **Send display name**, which
  lets a profile set to "Wise Old Man, name blank" fill in the name. The site
  never shows it.
- The write key is a secret config value. It goes only to the site in
  **Site** (https only, apart from a local dev server).
- On the Plugin Hub, plugins that send data to a third-party server carry a
  `warning=` line in their hub manifest, shown before install. The one for
  this plugin is drafted in `plugin-hub-manifest.txt`.

## Building and testing

Needs JDK 11+ (tested on 21).

```bash
./gradlew test    # 13 unit tests: payload, fingerprint, URLs, reply handling
./gradlew run     # a dev RuneLite client with this plugin (add DWMS for real data)
```

A live test uploads through the real client code to a running Bank Ledger
and checks stored, unchanged and a wrong key; it is skipped unless configured:

```bash
BANK_LEDGER_URL=http://127.0.0.1:8787 BANK_LEDGER_PROFILE=<id> BANK_LEDGER_KEY=<key> ./gradlew test
```

## Submitting to the Plugin Hub

1. Test in a real client with DWMS: bank close, the timer, logging into an
   alt with **Only for account** set, DWMS disabled (the "needs DWMS"
   message).
2. Push this repository to a **public** GitHub repo (it has the required
   BSD 2-Clause `LICENSE`; an `icon.png` up to 48x72 px is optional).
3. Fork <https://github.com/runelite/plugin-hub>, add
   `plugins/bank-ledger-uploader` with the contents of
   `plugin-hub-manifest.txt`, filling in the repository URL and the full
   40-character commit hash, and open a pull request.
4. Fix anything the CI build or the "RuneLite Plugin Hub Checks" bot flags by
   pushing a new commit and updating `commit=` in the same pull request.
5. Updates later are the same: a pull request that changes `commit=`.
