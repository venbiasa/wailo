---
adr: 0053
title: The USB listener rebinds on every return from the background, because a dead `NWListener` does not say it is dead (amends ADR-0037 and ADR-0046)
date: "2026-08-09"
status: accepted
relations: amends ADR-0037 and ADR-0046
date_source: git-commit
---
# ADR-0053 — The USB listener rebinds on every return from the background, because a dead `NWListener` does not say it is dead (amends ADR-0037 and ADR-0046)

- Status: Accepted; implemented in `sdk-ios`. `cd sdk-ios && swift test` is green (95).
- Context: ADR-0037 promised this, and its manual smoke checks it in so many words: lock the phone until USB drops, unlock, and the session returns "without a re-plug and without relaunching the app". It did not. Reported symptom — lock the phone for a while, come back to the app, and USB never reconnects however long you wait; only a relaunch fixes it. Both of ADR-0037's re-arms were gated on `listener == nil`: the failure-driven `rebind`, and the `didBecomeActive` observer. That gate holds only when `NWListener` reports its own death. iOS reclaims a suspended app's listening socket and the framework object frequently never notices — it sits in `.ready` with nothing behind it, or parks in `.waiting`, and neither of those is `nil`. So the one case the foreground hook was written for was the one case it could not fix, and it failed silently and permanently: Studio's `connectLoop` keeps dialling every two seconds and never gives up, the cable is fine, and the device simply never answers. ADR-0046 hardened the adjacent path — a bind that failed and was never retried — but that is the path where nothing was bound, not the path where the thing bound is a corpse.
- Decision:
  - **A return from the background rebinds unconditionally.** Nothing in Network.framework answers "can this listener still accept?", and the state it does report is precisely what lies here, so the only honest move is to stop asking and bind again — which costs milliseconds. The outgoing listener is cancelled and its bind generation retired first, so the `.cancelled` it is about to emit cannot schedule a rebind on top of the new one.
  - **`willEnterForeground` drives the rebind; `didBecomeActive` only covers a missing bind.** Only the former means the app really was in the background, which is the only time iOS takes the socket. A notification banner or Control Centre also makes the app active again, and rebinding on those would churn a listener that never lost anything.
  - **The accepted connection is left alone.** It is a separate socket that a fresh listener does not disturb; if it died in the background too, its keepalive fails it within a ping interval and Studio's next dial replaces it anyway. Closing it here would drop a session that survived a background short enough never to suspend the app.
- Alternatives considered:
  - **Probe the port with our own loopback connection and rebind only when it refuses:** rejected — a probe is indistinguishable from Studio at `accept`, so it would close the very session it was checking on, and it is more code than the rebind it exists to avoid.
  - **Treat `.waiting` after `.ready` as death as well:** rejected for now — plausible but unobserved here, and `rebind` closes the active connection, so a transient `.waiting` would drop a working session to fix something nobody has seen. A resume rebinds regardless.
  - **Stop the listener on `didEnterBackground` so the teardown is ours:** rejected — it gives up USB during a background the app may well survive, and still needs the foreground bind, so it only adds a second way to be wrong.
  - **Re-bind periodically while idle:** rejected — a timer that never sleeps, to cover a transition that already sends a notification.
- Consequences:
  - The lifecycle hooks are held as one array, and `stop` clears both, so a listener replaced by `setUsbPort` cannot leave an observer behind.
  - `testForegroundRebindsAListenerThatStillLooksAlive` drives the re-arm through a debug seam: `canImport(UIKit)` is false on a macOS test host, so the notification wiring itself stays manual-smoke-only and only the behaviour it triggers is covered. A healthy listener stands in for the zombie, because from inside the process the two are indistinguishable — which is the whole point. The assertion is that the bind generation moved, which is exactly what the old `listener == nil` gate prevented.
  - That same `canImport(UIKit)` means `swift test` never type-checks the branch this ADR is about, so an iOS-SDK compile is now part of verifying `sdk-ios`: `cd sdk-ios && xcodebuild -scheme WailoSDK -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build`.
  - Manual smoke: with USB connected, lock the phone and leave it long enough for the app to suspend (a minute or more), then unlock and return → the session comes back unaided, with no re-plug and no relaunch. Repeat by switching to another app for several minutes. Then switch away for two seconds and back, and pull Control Centre down over the app → the live session survives both.
