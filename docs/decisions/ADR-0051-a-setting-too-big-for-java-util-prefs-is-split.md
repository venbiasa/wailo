---
adr: 0051
title: A setting too big for `java.util.prefs` is split across keys, not moved out of it
date: "2026-08-09"
status: accepted
date_source: git-commit
---
# ADR-0051 — A setting too big for `java.util.prefs` is split across keys, not moved out of it

- Status: Accepted; implemented in `studio/shared` (`KeyValueStore.jvm.kt`). `cd studio && ./gradlew :shared:jvmTest` is green. Fixes a crash reachable from ADR-0019/0026's storage choice.
- Context: `Preferences.put` *throws* above `MAX_VALUE_LENGTH` (8192 chars) rather than failing softly, and five stores persist a whole list as a single string — Map Local (ADR-0019/0026), breakpoints, seeds, bookmarks and the capture filter. Map Local's encoding is the fattest, carrying a Base64 URL plus every response header per rule, so it reached the cap first: a dozen rules against real API URLs is already past 8 KB, and from there *every* edit threw from inside a Compose callback and took the window down. It read back fine on the next launch, because it was the save that was refused, not the load — so the failure presented as "editing a Map Local rule crashes Studio", with nothing about it suggesting a storage limit.
- Decision:
  - **Split an over-long value across numbered keys inside the JVM store**, transparently to callers. The cap is a property of this one backing store, not of any caller, so it is answered where it lives — and every existing store gets the headroom without being touched. The alternative shape, teaching each store to shard its own layout, is the same fix written five times.
  - **A chunk-count key is the switch, and the whole-value key is dropped when a value chunks.** Reads with no count key fall through to the plain key, so values written by every earlier build load unchanged — the same tolerate-the-older-shape migration the layout codecs use. Dropping the plain key keeps prefs from holding a second, stale copy of a setting the app no longer reads.
  - **A surrogate pair is never split.** Chunks are stored as separate strings, and a lone half is not text the platform backings can be trusted to return intact — a rule name with an emoji in it must not come back as replacement characters.
  - **The store still throws when a write genuinely fails.** Wrapping the save was tempting, since the visible symptom is a dead window, but it converts a crash into a silently dropped edit that the UI reports as saved, and the panel has no channel for telling anyone a save was lost.
- Alternatives considered:
  - **Move layouts to files under the app-data dir**, where Map Local bodies already live: rejected for now — it fixes one store and needs a migration per store, and the distinction still holds that bodies are opaque bytes read per request while a layout is a setting. Worth revisiting if a layout ever grows to a size where prefs is the wrong shape rather than merely a capped one.
  - **Compress before writing:** rejected — it buys a constant factor and then fails identically, and it costs the property that a value is legible in the plist when debugging what the app actually stored.
  - **One prefs key per rule:** rejected — grouping and order *are* the layout, so N independent keys make every reorder a multi-key transaction, which is exactly what prefs cannot offer.
- Consequences:
  - A chunked write is several `put` calls, so a crash mid-write can leave a torn value. Tolerable rather than fixed: the layout codecs already skip lines they cannot parse instead of throwing, so the worst case is losing rules, not a layout that refuses to load.
  - `PreferencesKeyValueStore` is `internal` rather than `private` so a test can point it at a throwaway node. The cap belongs to `Preferences`, so a fake would have passed this whole time while the app kept crashing — the regression is only provable against the real store.
  - Manual smoke: author Map Local rules until the layout is comfortably past 8 KB (a dozen rules with real URLs and a few headers each), then edit one and save → no crash. Quit and reopen Studio → every group and rule is still there, in order, and rules still serve. `~/Library/Preferences/com.apple.java.util.prefs.plist` shows `mapLocalRules.chunk.0` in place of `mapLocalRules`.
