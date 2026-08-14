---
adr: 0019
title: Map Local — device caches match-metadata only, fetches bodies lazily; rules are versioned + acked (anti-entropy)
date: "2026-07-17"
status: accepted
date_source: git-commit
---
# ADR-0019 — Map Local — device caches match-metadata only, fetches bodies lazily; rules are versioned + acked (anti-entropy)

- Status: Accepted; building. Supersedes the initial Map Local behavior (a `RuleSet` snapshot with response
  bodies inlined and cached whole on the device — that feature shipped without its own ADR; this records the
  model going forward).
- Context: The first Map Local cut pushed a `RuleSet` where each rule carried its response *bytes* inline, and
  the device cached the whole set (connection-scoped after the disconnect-clear change). Two problems drove a
  redesign: (1) **memory** — inlining bodies means the device holds every mapped file resident in RAM for the
  whole session, which is bad for large fixtures or many rules and fights invariant #3 (the SDK ships inside
  third-party apps; keep it light); (2) **silent desync** — pushes are fire-and-forget with no ack, so a push
  lost on a still-alive link leaves the device stale and the desktop unaware, with no repair until the next
  edit or reconnect. Query-the-desktop-per-request was considered and rejected: it taxes *all* traffic (even
  the ~99% that never match) with a round-trip, couples every request's latency to the desktop, and risks
  OkHttp dispatcher-thread exhaustion on Android. Map Local is automated (no human in the loop), so the match
  decision should stay local and instant; only the (occasional) matched body needs the authority.
- Decision:
  - **Device caches match-metadata only** (`MapLocalRule` = id, enabled, url_pattern, methods). Response bodies
    are never stored on the device. Cache stays connection-scoped (cleared on disconnect, ADR follows the
    existing device behavior).
  - **Lazy body fetch on match**: the device matches locally, then fetches the response (code, headers, body)
    from the desktop via a request-scoped RPC — `BodyRequest`/`BodyResponse` keyed by a `correlation_id`,
    with a device-side pending map. The request suspends until the reply arrives (event-driven on iOS
    `URLProtocol`; async/timeout on Android to avoid blocking OkHttp threads). Bodies live in device memory
    only for the life of that one request, and are always fresh (authority-sourced).
  - **Fail-open**: if the fetch can't complete (not connected, timeout, `found=false`, or a disconnect
    mid-fetch), the device makes its own real network call. We are not a proxy — the SDK always owns the real
    request — so a desktop hiccup degrades to live traffic, never a hung/failed request.
  - **Anti-entropy sync for the metadata**: `RuleSet` carries a monotonic `epoch` owned by the desktop. The
    device applies each snapshot as a full replace (unconditionally) and returns `RuleAck{epoch}`. The desktop
    tracks per-session `ackedEpoch` and re-pushes on a timer while a session is behind, so a lost push
    self-repairs; a reconnect re-syncs from scratch. `epoch` never gates application (only ack-matching, retry,
    and future sync-status display), so a desktop restart resetting the counter is harmless.
  - **Engine stays headless**: body resolution is a UI-agnostic `MapLocalBodyProvider` seam the desktop
    supplies (it reads the file for a given rule id). The engine never learns file paths and never reads the
    filesystem itself.
- Alternatives considered:
  - **Query-per-request availability** (no cache): rejected for the whole-traffic latency/thread cost above.
  - **Inline bodies in the snapshot** (the initial model): rejected for memory + bandwidth (holds all fixtures
    resident; re-pushes all bytes on every edit).
  - **No ack, reconnect-only repair**: leaves the silent-push-loss window open until the next edit/disconnect.
- Consequences:
  - The socket becomes bidirectional request/reply: versioned rule pushes + `RuleAck` + a `BodyRequest`/
    `BodyResponse` correlation channel. This is the same correlation/pending-map machinery breakpoints will
    need — built once here.
  - Device Map Local memory is now ~O(rule count × small metadata), independent of fixture size; a matched
    request pays one device↔desktop round-trip (only on matches).
  - **Android does not yet apply rules** (it ignores inbound frames); when it does, it must mirror this model
    — metadata cache, lazy fetch, fail-open, ack — since consistency across the two SDKs is kept only by the
    wire contract (invariant #4), never by shared device code.
  - Desync is now both self-repairing (ack + retry + reconnect) and observable (the desktop knows each
    device's `ackedEpoch` vs the current `epoch`), enabling a future "rules out of sync" indicator.
