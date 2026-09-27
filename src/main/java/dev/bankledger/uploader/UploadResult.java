package dev.bankledger.uploader;

import java.util.List;
import lombok.Value;

/** What the site said about one upload. */
@Value
class UploadResult {
  enum Outcome {
    /** A new snapshot was stored. */
    STORED,
    /** The bank matched the latest snapshot; nothing was stored. */
    UNCHANGED,
    /** The profile id or write key is wrong. Don't retry until the settings change. */
    REJECTED,
    /** Rate limited, read-only or over the daily cap. Try again later. */
    DEFERRED,
    /** Anything else: network trouble, a server error. */
    FAILED
  }

  Outcome outcome;
  String message;
  List<String> staleStorages;
  long actualWorth;
}
