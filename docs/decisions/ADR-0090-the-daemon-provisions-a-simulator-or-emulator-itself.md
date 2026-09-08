---
adr: 0090
title: The daemon provisions reachable proxy targets and reports the route and trust it confirmed
date: "2026-08-25"
status: accepted
relations: gives ADR-0076's manual setup an automatic path for targets that have one; supersedes ADR-0076's rejection of a magic host for post-route checks; extends ADR-0075's machine-mutation discipline to devices; keeps ADR-0083's required machine seam
---
# ADR-0090 — The daemon provisions reachable proxy targets

- Status: Accepted and implemented as `ProxyTargetProvisioner` in `daemon`, three RPCs, a card in Studio's
  Devices panel, and equivalent CLI and MCP tools. Decided while asking where a proxy setup wizard belongs,
  then extended when physical-device and AI-agent parity were chosen explicitly.
- Context: ADR-0070 added the proxy, ADR-0073 put its root on the desktop, and ADR-0076 connected the two
  ends with a page the device fetches for itself. That covers a physical phone, and a phone is the *minority*
  of what gets debugged: most sessions are a booted simulator or emulator on the same machine, where Wailo
  already has the tooling to configure the device without asking the user to type anything. An Android
  phone that has already authorized ADB is equally addressable for routing, even though an ordinary
  production phone still requires a person to approve a user root. Nothing used either capability.
  Worse, the Devices panel — the place someone goes when their traffic is not showing up — listed three ways
  in, all of which assume the app hosts the SDK, so a release build or someone else's app had no path from
  there at all. The established tools all solve exactly this case automatically, and it is the case where
  automation is possible: a simulator inherits the Mac's network settings, and an emulator answers to adb.
- Decision:
  - **Detection first, instructions second.** Opening the setup card lists what can be configured *now* and
    offers one click each; the manual steps for a phone sit below it. A wizard whose first screen is a
    numbered list is the wrong shape when the machine can do the work.
  - **It lives in the daemon, not Studio.** The daemon already owns adb (ADR-0058), and a feature that only
    works with a window open is one the daemon does not own (invariant #2, ADR-0066). So the CLI provisions a
    simulator too, and an `xcrun` call never appears in `desktopApp`.
  - **The three kinds reach the listener differently, and that decides what has to be on.** A simulator has no
    proxy setting of its own, so setting one up turns the system proxy on (ADR-0075). An emulator is pointed
    at `10.0.2.2` — the host's loopback seen from inside it — so the listener merely has to be up, and this
    path never needs the LAN bind at all. That is a security property, not a convenience: the wide bind is an
    open relay while it runs (ADR-0077), and the most common target now never asks the user to accept one.
    A physical Android target is pointed at the Mac's current LAN address; that temporarily leases the LAN
    bind unless the user had already enabled or subsequently claims it.
  - **Which trust store took the root is reported, never promised.** `system` is trusted by every app there;
    `user` only by a build that opted in. On an emulator the daemon tries system, falls back to user, and
    reports `none` with the reason when neither is writable — which is what a Play Store image looks like,
    since it refuses `adb root`. Reporting the store is the whole verdict, because "installed" is a useless
    answer to someone debugging an APK they did not compile.
  - **Every mutation is prepared, verified, and recoverable.** The exact previous Android proxy is atomically
    recorded before it changes. Certificate destinations are recorded before bytes are copied, owned paths
    are verified by SHA-256 and never overwrite an unowned same-subject root, and only those paths are
    removed. Stable emulator identity survives a changing adb serial. External commands are bounded by a
    deadline, disconnected cleanup remains visible, and the daemon retries recovery when the target returns.
  - **The proxy half is recorded before the trust store is touched.** A pointed-at-nothing emulator has no
    network; a missing certificate only means HTTPS stays a tunnel, which is ADR-0071's default. So the
    undoable half is made undoable first, and a run that fails afterwards still leaves a device recoverable.
  - **The record lives beside the system-proxy one, outside `WAILO_HOME`.** Same reasoning as ADR-0078: a
    target's global proxy outlives the daemon that set it, so the note saying how to put it back has to be
    findable by whichever daemon runs next — not deleted with a scratch run's temp directory. Start-up
    recovers from it before anything else runs.
  - **`DeviceTool` is a required seam, with `forThisMachine()` the only door to the real binaries.** Exactly
    ADR-0083's rule, for the same reason: a test that could pair a throwaway record with a real `adb` would
    reconfigure the emulator of whoever ran the suite.
  - **A copy into `/system` is confirmed, not assumed.** `cp` exits 0 on a partially remounted `/system` and
    the file never lands, which would otherwise be reported as the store that makes release builds work.
  - **Post-route setup and checks use `wailo.test`.** ADR-0076 rejected a magic host as the primary way to
    reach an unconfigured proxy. Here the phone has already been pointed at Wailo, and `.test` is permanently
    reserved rather than a name that can become a real TLD, so the proxy can answer it without DNS or a
    self-loop through its own LAN address.
  - **Manual phones get a QR and a local HTTPS self-check, not a claim of automation.** No unsupervised URL
    can write an iPhone's Wi-Fi proxy or approve a root. The QR only opens the existing setup page. Its
    `wailo.test` check succeeds through the proxy only when that browser reaches Wailo and trusts the
    current root; it does not claim that a pinned app or Android release build will trust it.
  - **CLI and MCP expose the same explicit controls and structured outcome.** AI tools may list before they
    mutate, but setup, LAN exposure, system-proxy takeover, certificate rotation, and cleanup are separate
    named calls that require `confirm=true`. A read-only setup guide reports blockers and restoration state
    without minting a root. Partial success remains an error while still reporting the route, trust store,
    stale-root, pending-cleanup, and required-action fields.
- Alternatives considered:
  - **A new tool-rail icon and a dedicated Proxy panel.** Rejected: the rail docks tools you keep open beside
    the traffic list, and this is a task you finish once per device. The proxy has arguably outgrown a
    Settings section, but that is a separate decision from adding this.
  - **A stepped wizard in Settings → Proxy.** Rejected: Settings is where individual knobs live, and someone
    whose device is missing goes to Devices. Putting it there also let the SDK-less route be named in the
    empty-devices text, which is the sentence that was actively misleading before.
  - **Run `xcrun`/`adb` from `desktopApp`, where the QR encoder already lives.** Rejected under invariant #2:
    a headless session could then never set a simulator up, and the feature would die with the window.
  - **Treat a QR as automatic device configuration.** Rejected: no URL scheme on either platform can write
    Wi-Fi proxy settings, and a profile that could do so needs the Wi-Fi password or a supervised device.
  - **Probe for a writable system image while listing targets.** Rejected: `adb root` restarts adbd, which is
    too intrusive to do just to draw a row. The fork is discovered where it is acted on.
  - **Reset the simulator keychain on release, so the certificate comes out.** Rejected: `simctl keychain
    reset` takes that simulator's app credentials with it. Releasing says the root stays instead, because a
    surprising side effect is worse than an honest limitation.
- Consequences:
  - The control protocol bumps 15 → 16, so a frontend built with this restarts an older daemon on launch.
  - Targets are fetched on demand, never polled: listing shells out once per device, which is not a per-tick
    cost, so `PollResponse` is untouched and the card renders a real loading state.
  - Releasing a simulator gives the Mac back but cannot untrust the root there — stated in the outcome rather
    than hidden. Erasing that simulator is the only way to remove it.
  - Android API 34+ moved the CA bundle into the Conscrypt APEX, so `/system` will not remount there and those
    emulators land in the user store. The copy is verified, so this degrades into an accurate `user` rather
    than a false `system`.
  - The provisioner is covered against stand-in tools with no device attached; `forThisMachine()` is the only
    construction the suite cannot reach.
  - Manual smoke: boot an emulator on a Google APIs image, open Devices → Proxy → Set up, and
    confirm it reports `system` trust and that an HTTPS request from a release build in it appears decrypted
    once its host is unlocked. Then Release, and confirm the emulator has a network again. Repeat on a Play
    Store image and confirm it reports `none` and names the remaining step. On a booted simulator, confirm
    setting up turns the system proxy on and that releasing turns it back off. On an ADB-authorized phone,
    confirm setup uses the LAN address, stages a root when system trust is unavailable, and restores the
    exact prior proxy. Scan the manual QR on both platforms and run the browser trust check.
