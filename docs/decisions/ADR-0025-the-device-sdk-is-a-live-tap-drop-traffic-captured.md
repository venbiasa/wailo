---
adr: 0025
title: The device SDK is a live tap — drop traffic captured while disconnected instead of buffering a replay backlog (amends ADR-0018)
date: "2026-07-22"
status: accepted
relations: amends ADR-0018
date_source: git-commit
---
# ADR-0025 — The device SDK is a live tap — drop traffic captured while disconnected instead of buffering a replay backlog (amends ADR-0018)

- Status: Accepted; building. `:sdk-android:testDebugUnitTest` green (renames `retriesUntilServerIsUp` →
  `dropsWhileDownButStreamsAfterReconnect` and asserts an exchange captured while down is dropped, not replayed);
  `sdk-ios` `swift test` green (same rename, plus the post-drop send now waits for the reconnect Hello).
- Context: the desktop `engine` keeps captured exchanges in memory only — it persists nothing across its own
  restart (ADR-0003/0008). Yet both device SDKs buffered captured exchanges in a bounded drop-oldest queue
  while the desktop was down and flushed the whole backlog on reconnect (ADR-0008/0010/0011), and ADR-0018
  additionally requeued an in-flight exchange on a mid-send failure. Observed consequence: after the desktop
  crashed and was relaunched minutes later, the still-running device replayed up to ~512 stale exchanges, so
  "old traffic" reappeared in a freshly started inspector even though the desktop itself kept nothing. For an
  interactive inspector the intuitive contract is a live tap — you see what is happening while you are
  connected, not a backlog from while you were away.
- Decision:
  - Make both device SDKs a *live tap*: an exchange is delivered only while a connection is live and dropped
    otherwise; nothing is retained across a disconnect, so a reconnect never replays past traffic.
    - Android (`sdk-android` `WailoClient`): replace the long-lived `outbox` with a per-connection `Channel`
      held in a `@Volatile live`. `onExchange` does `live?.trySend(...)` (drops when null). The channel is
      created once the WebSocket is open, set as `live`, drained after Hello, and on disconnect `live` is
      cleared and the channel discarded. The ADR-0018 mid-send requeue is removed (a failed send drops it).
    - iOS (`sdk-ios` `WailoClient`): `onExchange` enqueues only while `task != nil`, and `handleDisconnect`
      clears the buffer so nothing survives a drop.
  - Keep every recovery mechanism ADR-0018 added: the always-on reconnect loop, the ~2s backoff, and the ping
    keepalive that detects a silently dropped idle link. A stale/lost connection still self-heals for *future*
    traffic — only the replay of *past* traffic is removed. The bounded drop-oldest buffer (cap 512) is
    retained solely to keep `onExchange` off the caller's thread and to smooth a burst on a live link; being
    discarded on disconnect, it is not a cross-outage store.
- Alternatives considered:
  - **Per-exchange TTL** so only "recent" backlog replays. Rejected: still a surprising partial replay, and it
    couples the wire to wall-clock/skew for little benefit on an interactive tool.
  - **Persist capture on the desktop** so a restart restores the list. Rejected as orthogonal and explicitly
    unwanted here — the ask was to *stop* stale traffic reappearing, not to make it durable.
- Consequences:
  - After a desktop crash/restart a freshly started inspector shows only traffic captured after it reconnects —
    no stale backlog. The two SDKs stay behaviorally aligned (live tap) and consistent with the desktop's own
    no-persistence model.
  - Traffic generated while the desktop is down, or during the reconnect gap, is now genuinely lost by design;
    a request that races a mid-send drop is also lost (no requeue). Acceptable for an interactive inspector — a
    future headless/record mode that must not lose traffic would need its own durable sink (its own ADR), not
    this live path.
  - Amends the "drain the buffer / buffered exchanges wait" wording of ADR-0008/0010/0011 and the ADR-0018
    requeue; ADR-0018's reconnection + keepalive mechanism is otherwise unchanged.
  - Manual smoke: with a device streaming, kill the desktop, keep the app active a few minutes, relaunch — the
    list starts empty and fills only with new requests.
