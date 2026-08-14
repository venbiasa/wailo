---
adr: 0050
title: Studio installs the `adb reverse` route itself, and an attached Android device is a row in the Devices panel
date: "2026-08-08"
status: accepted
date_source: git-commit
---
# ADR-0050 — Studio installs the `adb reverse` route itself, and an attached Android device is a row in the Devices panel

- Status: Accepted; implemented in `studio/desktopApp`. `cd studio && ./gradlew :desktopApp:test` is green. Extends ADR-0037 to Android. The poll loop below is superseded by ADR-0052.
- Context: `adb reverse tcp:8899 tcp:8899` has been the first line of the Android setup since ADR-0004, typed by hand, re-typed after every reconnect, and re-typed again whenever the capture port moved (ADR-0036 shipped a help string telling people to). Meanwhile iOS devices on the cable appear in the Devices panel by themselves (ADR-0037). The asymmetry is the whole gap: a plugged-in Android phone is invisible to Studio until the user does something in a terminal.
- Decision:
  - **Watch for attached devices and install the reverse mapping for every ready one.** Originally a two-second `adb devices -l` poll, on the reasoning that pushing would mean an undocumented protocol; ADR-0052 corrects that and moves the watching to `host:track-devices`, keeping the poll as the fallback. Installing the mapping is still the CLI — the interface Google keeps stable, already on every Android developer's machine.
  - **`adb` is searched for in the SDK's own locations before `PATH`.** An app launched from Finder inherits the launchd environment, not the shell's, so the `platform-tools` entry in someone's `.zshrc` is simply absent — a `PATH`-only lookup would fail for most users while working perfectly under `./gradlew run`.
  - **The manager owns the route, not a connection.** This is deliberately not shaped like `UsbDeviceManager`: usbmux has Studio dial *into* the device, so it owns a `DeviceConnection` and calls `engine.attach`. A reverse mapping only installs a route — the device stays the WebSocket client and arrives on the ordinary capture server, on loopback, needing no pairing. There is nothing to attach.
  - **Moving the capture port re-forwards every device.** Both ends of the mapping are that port, and leaving the old one in place routes the phone at a socket nobody is listening on. This is the manual step the settings help text used to describe; the text now describes what happens instead.
  - **A session is claimed by an adb row only when there is exactly one of each.** Nothing on the wire ties a loopback session back to a serial, and nothing can. With one device and one Android loopback session — the case this feature is for — "Connected" on the row is certain. With more of either, the tunnel rows and the session rows stand apart: saying less beats pairing the wrong phone with the wrong app.
  - **An unauthorised device gets a row, not silence.** "Plugged in, waiting for you to tap Allow" is the single most common reason a phone does not appear, and it is invisible from inside Studio unless the panel says it.
- Alternatives considered:
  - **A settings toggle for automatic forwarding:** rejected for now — `UsbDeviceManager` sets the precedent of auto-detecting and just working, and this runs the Android SDK's own tool doing exactly what the README told the user to type. A toggle can be added when someone wants it off.
  - **Bundle or require a specific adb:** rejected — the platform-tools are versioned against the device, not against Studio, and shipping a second copy is how you get "adb server version mismatch" as a support burden.
  - **Tear the mapping down on quit:** rejected — a reverse mapping costs the device nothing, and removing it would break the common habit of leaving an app running and reopening Studio.
  - **Give each device its own port so sessions can be correlated:** rejected — the SDK's zero-config default dials the capture port on `localhost` and has no way to be told about a different one, so this would trade an ambiguous row for a device that does not connect at all.
- Consequences:
  - `ConnectedDevice` gained `loopback`, read off the pre-admission connection (for a LAN peer `isTrusted` is exactly "arrived on loopback", which the sealed wrapper no longer says). Nothing in `Hello` can express it, and it is what lets the desktop tell an Android device on the cable from one on Wi-Fi — they arrive on the same server over the same transport.
  - `DeviceTransportKind` gained `ADB` and `DeviceConnectionStatus` gained `UNAUTHORIZED`; the empty-devices text now names only what this machine can actually do, since iPhone USB is macOS-only and adb may be missing.
  - `parseDevices` is split from the process call and tested, which is how the header-location bug was found: on a cold start adb prints `* daemon not running…` *before* its header, so counting lines from the top read the header itself as a device.
  - Manual smoke: quit Studio, run `adb reverse --remove-all`, plug in a phone, start Studio → the device appears within a couple of seconds and the app connects with no terminal. Change the capture port in Settings → the device drops and comes back on the new port unaided. Lock the phone and revoke USB debugging authorisations → the row says it is waiting for permission; accept the prompt → it recovers. Unplug → the row disappears.
