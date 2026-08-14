---
adr: 0018
title: Device-side WebSocket reconnection is self-healing (delegate + generation + ping keepalive); OS reachability deferred
date: "2026-07-17"
status: accepted
date_source: git-commit
---
# ADR-0018 — Device-side WebSocket reconnection is self-healing (delegate + generation + ping keepalive); OS reachability deferred

- Status: Accepted and built. `swift test` green (adds `testReconnectsWhenServerStartsLate` and
  `testReconnectsAfterServerDrops`); `:sdk-android:testDebugUnitTest` green (adds `retriesUntilServerIsUp`).
- Context: ADR-0008/0010 specified "a background loop drains and auto-reconnects", but the two device ports
  diverged in how robustly they actually recover. Android's Ktor loop re-runs on *every* connection outcome
  (refused, dropped, closed), so it keeps retrying. The Swift port scheduled the next attempt *only* inside
  `handleDisconnect()`, which ran *only* when `URLSessionWebSocketTask` delivered a `send`/`receive` failure
  callback. `URLSessionWebSocketTask` does not guarantee those handlers fire on every failure — notably a
  connection that never establishes (desktop not up) or a silently dropped idle link — so a single missed
  callback left iOS "waiting" with no retry ever scheduled: it would not reconnect when the network came
  back. Separately, neither port detected a silently half-open *idle* socket (no traffic to fail on), and
  neither reacts to OS connectivity changes, so recovery was at best a blind poll.
- Decision:
  - iOS (`sdk-ios` `WailoClient`): make the transport `URLSession` carry a delegate and route
    `urlSession(_:task:didCompleteWithError:)` / `didCloseWith` into the same disconnect path. That delegate
    signal fires even when the `send`/`receive` handlers don't, so a failed or never-established attempt
    still schedules a retry — the fix that makes reconnection impossible to wedge. Guard every callback with
    a monotonic `generation` epoch (bumped on connect *and* disconnect) so a late/duplicate signal is a
    no-op and each disconnect retries exactly once. Add a `sendPing` keepalive while connected to provoke a
    failure (and reconnect) on a dead idle link. `stop()` calls `invalidateAndCancel()` on the session to
    break the delegate retain cycle — otherwise a replaced client (e.g. a second `Wailo.start`) leaks a live
    WebSocket.
  - Android (`sdk-android` `WailoClient`): set the Ktor client `pingIntervalMillis` so a dead idle link is
    actively probed, and consume `incoming` as the "await close" signal so a ping/pong timeout, drop, or
    server close ends the session block and the existing loop reconnects — draining `outbox` alone never
    observes an idle drop. Requeue the in-flight exchange when a send fails, so a mid-send drop doesn't lose
    it across the reconnect.
  - Keep the existing fixed ~2s reconnect backoff on both; the ping interval is ~20s.
- Alternatives considered:
  - **OS reachability monitoring** (`NWPathMonitor` on iOS, `ConnectivityManager` on Android) for an
    *instant* reconnect the moment connectivity returns. Deferred: the self-healing retry already recovers
    without it, and the Android side would add an `ACCESS_NETWORK_STATE` permission to a library that ships
    inside third-party apps (invariant #3) — an imposition that warrants its own decision. `NWPathMonitor`
    needs no permission and can be added on iOS alone later.
  - **Exponential backoff**: unnecessary at a 2s interval to a single localhost/LAN peer; revisit only if it
    ever hammers a busy network.
- Consequences:
  - Both device SDKs now reliably reconnect after the desktop starts late, quits, or the link drops or goes
    idle — no app restart — and this is proven by machine (loopback reconnect tests), not just by
    inspection.
  - The two ports stay aligned on the wire contract but differ in mechanism (URLSession delegate + ping vs.
    Ktor pinger + `incoming`), the accepted device-side duplication of ADR-0010/0011.
  - Recovery latency is still bounded by the 2s poll (plus up to the ping interval to notice a *silent* idle
    drop), not instant; closing that gap is the deferred reachability follow-up above.
