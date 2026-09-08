# Bundled proxy physical smoke

Keep this runbook in the repository: it covers the parts of the proxy that no test can prove — a real
phone's trust store, the OS network settings this machine actually has, and what a locked host looks like
to a human. The checklist is intentionally evergreen and must remain unchecked in source. Record each
execution under **Run history** instead of turning one successful build into permanent checkmarks.

Run against a disposable daemon so the primary one keeps its settings and its capture:

```bash
export WAILO_HOME=$(mktemp -d) WAILO_CAPTURE_PORT=8991 WAILO_PROXY_PORT=9191
export WAILO_KEYCHAIN_SERVICE="wailo-proxy-smoke" WAILO_IDLE_LINGER_MINUTES=0 WAILO_NO_MENUBAR=1
cd studio && ./gradlew :cli:installDist
```

The exception is the system proxy row: it edits *this machine*, so it is the same machine either way —
run it last, and confirm the settings came back.

## Device setup paths

- **Android emulator:** boot it, open Devices → Without the SDK, and choose Set up. Wailo uses
  `10.0.2.2`, preserves the emulator's previous proxy, and reports `system`, `user`, or `none` only after
  checking where the root actually landed.
- **Physical Android:** enable Developer options and USB debugging (or Wireless debugging), connect through
  ADB, approve this Mac, and keep the phone on a network that can reach it. Then use the phone's row in the
  same card. Wailo sets the reachable LAN address automatically. A production phone normally refuses
  `adb root`, so Wailo stages the root in Downloads and shows the exact approval step on the phone.
- **iOS Simulator:** boot it and choose Set up. The simulator rides the Mac system proxy. Releasing restores
  the Mac, but `simctl` cannot remove one root without resetting the whole simulator keychain.
- **Physical iPhone or iPad:** enable LAN access in the card, enter the shown host and port under
  Settings → Wi-Fi → network info → Configure Proxy → Manual, then scan the setup QR. Install the profile
  and separately enable it under Settings → General → About → Certificate Trust Settings.

After the phone's proxy is set, `http://wailo.test/route-check` proves routing and
`https://wailo.test/check` proves that browser accepts the current root. The setup page's **Check browser
trust** action runs the second check. Neither overrides certificate pinning or makes an Android release
app trust user-installed roots.

The same workflow is available headlessly through `proxy_targets`, `setup_proxy_target`, and
`clear_proxy_target`; `proxy_setup_guide` gives the dynamic manual path. MCP callers start with
`get_proxy_setup_guide`, and pass `confirm=true` to every proxy mutation. Responses carry routing,
confirmed trust, stale-certificate, pending-cleanup, required-action, and restoration fields.

## Release gate

Before accepting an ordinary build, run Desktop capture, Locked HTTPS, Unlocked HTTPS, and System proxy
restore. Before releasing a change to the certificate authority or the takeover, run the full matrix on
both a physical iPhone and a physical Android device.

## Evergreen matrix

- [ ] **Desktop capture:** start the proxy from Studio's Settings, point a browser or
  `curl --proxy http://127.0.0.1:9191` at a plain HTTP origin, and confirm the row appears with the
  `Proxy` column ticked and the client column showing the peer.
- [ ] **Locked HTTPS:** with nothing unlocked, load an HTTPS page through the proxy. Confirm the page
  loads normally, the row is a `CONNECT` whose response says it was not decrypted, and no request inside
  the tunnel appears. "Not decrypted" must be legible as a choice, not as traffic Wailo missed.
- [ ] **Unlocked HTTPS:** create the certificate from Settings, trust it in the login keychain, add the
  host, and reload. Confirm the `CONNECT` row is replaced by ordinary rows with readable bodies, and that
  a host you did *not* add is still tunnelled in the same session.
- [ ] **Untrusted root:** unlock a host *without* trusting the certificate. Confirm the browser refuses
  with its own certificate warning and Studio still shows the attempt — installing and trusting are two
  steps, and this is the one users skip.
- [ ] **iPhone:** turn on the LAN bind, set the phone's Wi-Fi proxy to the reported address, and browse to
  `http://wailo.test/setup`. Confirm the setup page loads, the certificate installs as a profile, and — only after
  Settings → General → About → Certificate Trust Settings — an unlocked host decrypts. Confirm an app
  with pinned certificates still refuses, and that this reads as the app working rather than Wailo
  failing.
- [ ] **Android:** same, through Settings → Security → Encryption & credentials → Install a certificate →
  CA certificate. Confirm a debug build that opts into user CAs decrypts and a release build does not.
- [ ] **Physical Android one-click:** authorize ADB, choose the phone's Set up row, and confirm its previous
  proxy is restored byte-for-byte by Release. On an unrooted phone, confirm the row reports `none`, stages
  the root in Downloads, and names the remaining approval step.
- [ ] **QR and trust check:** scan the setup QR, finish the platform trust steps, and confirm Check browser
  trust succeeds. Remove or rotate the root and confirm it fails until the current root is trusted.
- [ ] **No root, no mint:** with the root removed, load the setup page from a device and confirm it says
  where to create one and that `proxy_status` still reports `ca=none`. A device on the network must never
  be what creates a signing key on this machine.
- [ ] **Rotation:** replace the certificate while a decrypted host is loaded. Confirm the browser starts
  refusing until the new root is trusted, and that the fingerprint in Settings matches what the keychain
  shows.
- [ ] **System proxy restore:** turn on "Send this Mac's traffic through Wailo", confirm ordinary browsing
  is captured, then turn it off and confirm System Settings → Network → Proxies is exactly as it was.
- [ ] **System proxy after a kill:** turn it on, then `pkill -9 -f com.venbiasa.wailo.daemon.MainKt`.
  Confirm the machine is still pointed at Wailo (it is — nothing ran), then start any frontend and
  confirm the next daemon restores the settings at start-up before anything binds.
- [ ] **Chaining:** on a machine already behind a proxy, turn the takeover on and confirm browsing still
  works and `proxy_status` reports `via=`. A machine that loses its only route out is the worst failure
  this feature has.
- [ ] **Breakpoints and Map Local on proxy traffic:** arm one of each against a proxied host and confirm a
  hold appears in the breakpoint window and resolves, and that a mapped response is served without the
  origin being contacted.

## Recovery and cleanup

If the machine loses its network, the takeover is the first suspect: `wailo-cli set_system_proxy --off`,
or clear the proxy rows in System Settings → Network → Proxies by hand. The daemon's snapshot lives in
the real machine-state directory, outside `WAILO_HOME`, so starting any Wailo daemon can restore it.

Remove the test root from the login keychain and from every device it was installed on — a trusted root
that outlives the run is the one piece of debris that matters. Then stop the disposable daemon and delete
its state directory and Keychain service.

## Run history

<!-- Append one section per physical execution: date, build, devices, what passed, what was found. -->
