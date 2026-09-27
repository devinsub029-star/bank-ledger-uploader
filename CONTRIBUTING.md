# Contributing

## Building and testing

Needs JDK 11+ (tested on 21).

```bash
./gradlew test    # unit tests: payload, fingerprint, URLs, reply handling
./gradlew run     # a dev RuneLite client with this plugin (add DWMS for real data)
```

A live test uploads through the real client code to a running Bank Ledger
and checks stored, unchanged and a wrong key; it is skipped unless configured:

```bash
BANK_LEDGER_URL=http://127.0.0.1:8787 BANK_LEDGER_PROFILE=<id> BANK_LEDGER_KEY=<key> ./gradlew test
```

Before a release, check in a real client with Dude, Where's My Stuff?
installed: an upload when the bank closes, no upload when nothing changed,
the timer, **Only for account** with an alt, a wrong key, and DWMS disabled
(the "needs Dude, Where's My Stuff?" chat message).

## Releasing on the Plugin Hub

The plugin is published through
[runelite/plugin-hub](https://github.com/runelite/plugin-hub), whose
`plugins/bank-ledger-uploader` file names this repository and a commit, with
a `warning=` line saying what data is sent to a third-party server.

To release a new version, push it here, then open a pull request to
plugin-hub that changes `commit=` in that file to the new full 40-character
hash. Fix anything its CI build or the "RuneLite Plugin Hub Checks" bot flags
by pushing again and updating `commit=` in the same pull request.
