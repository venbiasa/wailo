---
adr: 0052
title: Attached Android devices are watched over `host:track-devices`, not polled (supersedes ADR-0050's poll loop)
date: "2026-08-09"
status: accepted
relations: supersedes ADR-0050's poll loop
date_source: git-commit
---
# ADR-0052 — Attached Android devices are watched over `host:track-devices`, not polled (supersedes ADR-0050's poll loop)

- Status: Accepted; implemented in `studio/desktopApp`. `cd studio && ./gradlew :desktopApp:test` is green. Reverses one bullet of ADR-0050; everything else in it stands.
- Context: ADR-0050 chose to poll `adb devices -l` every two seconds, on the reasoning that pushing "would mean speaking the adb server's undocumented socket protocol". That reasoning was wrong on the facts. `host:track-devices` is described in AOSP's own `SERVICES.TXT`, and the long form `host:track-devices-l` returns the same text `adb devices -l` prints, minus the header — so `parseDevices` reads it unchanged. Measured on a developer machine, each poll costs ~13.6 ms of CPU: 0.68% of a core held continuously, ~24 CPU-seconds an hour, forever, almost all of it spent learning that nothing changed. Small, but it is a timer that never sleeps and forks a process on every tick, which is the shape of cost that shows up in a laptop's battery menu rather than in a benchmark.
- Decision:
  - **A single tracking connection to the adb server replaces the poll loop.** One socket to port 5037, one length-prefixed request, and the server writes a new device list on every change: no wakeups while idle, and a device appears the instant it is plugged in instead of up to two seconds later.
  - **Polling survives as the fallback, and as the bootstrap.** There is nothing to track before an adb server exists, and `adb devices` is what starts one — so the fallback is not dead weight kept for symmetry, it is on the normal cold path. An adb too old to track at all degrades to exactly ADR-0050's behaviour, at the same interval.
  - **The long form is asked for first and the short form accepted.** `-l` carries `model:`, the only part of a device list a human recognises; an adb that rejects it still answers the short form, where the serial has to do.
  - **The host port is folded into the reconcile rather than read from it.** Under polling, a capture-port change took effect on the next tick for free. With a pushed list nothing arrives when the user edits a field in Settings, so the port is combined with the device list into a single reconcile that is also the only writer of the tunnel state — which is what keeps `installed` consistent without a lock.
- Alternatives considered:
  - **ddmlib (`AndroidDebugBridge.addDeviceChangeListener`):** rejected — it is the supported callback API and Android Studio's own, but it is a heavy Google artifact that wants to manage the adb server's lifecycle, to replace ~100 lines that speak four hex digits and a string.
  - **Keep polling, just slow it down:** rejected — it trades the cost against the latency, and both are the thing being fixed.
  - **Drop the poll fallback once tracking works:** rejected — it is what starts the server, so removing it means Studio only sees devices when something else already ran adb.
- Consequences:
  - A blocking socket read does not answer to thread interruption, so cancellation has to close the socket underneath it. A completion handler on the reading coroutine cannot: a coroutine stuck in that read never *completes*, it sits in cancelling, so the handler fires after the deadlock it was meant to prevent. The closer is a sibling coroutine parked on `awaitCancellation`. This was not reasoned out in advance — it was a real hang, found by the test below and diagnosed off a thread dump.
  - Closing the socket to break the read makes cancellation arrive as an `IOException` indistinguishable from adb dying, so the tracker checks whether it is still active before reporting one.
  - `AdbTrackerTest` runs against a scripted fake server for the shapes that matter — an empty frame meaning "nothing attached" rather than a hang-up, a rejected long form, a truncated length — plus one test against the *real* adb server, skipped where there is none. The unofficial service is the risk this ADR takes on, and a fake proves nothing about a future platform-tools dropping it; that test also fails by hanging if the cancellation path above ever regresses.
  - Manual smoke is unchanged from ADR-0050, plus: with Studio open and a device attached, `adb kill-server` → the row drops and returns within a couple of seconds unaided; unplug and replug → the row updates immediately rather than on a tick.
