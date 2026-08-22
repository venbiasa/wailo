---
adr: 0075
title: The system proxy takeover snapshots to disk and chains through whatever was already there
date: "2026-08-22"
status: accepted
relations: extends ADR-0070 and ADR-0074; the automation half of the macOS setup story
---
# ADR-0075 — The system proxy takeover snapshots to disk and chains through whatever was already there

- Status: Accepted; implemented as `SystemProxyController` in `daemon`, a `ProxyChain` seam in `:proxy`, `wailo-cli set_system_proxy`, a Settings switch in Studio, and a menu bar row — the takeover rewrites the machine's network configuration, so it has to be visible and undoable with no window open (ADR-0066). Verify with `cd studio && ./gradlew :proxy:test :daemon:test`.
- Context: Pointing a browser at the bundled proxy by hand is several steps in System Settings, and it is the step users get wrong — most often by leaving it on after Wailo stops, at which point the machine has no network and the cause is not obvious. Automating it means a debugging tool editing the OS network configuration, which raises two problems that dwarf the convenience. First, a machine already behind a proxy — a corporate laptop, a VPN's PAC, a local `mitmproxy` — has that setting *as its only route out*; overwriting it does not degrade the connection, it removes it. Second, whatever Wailo writes has to come back off, including when Wailo is killed rather than closed.
- Decision:
  - **Snapshot before the first change, and never re-read.** `networksetup -getwebproxy` / `-getsecurewebproxy` is captured for every active service before anything is written. Restore replays that snapshot. Reading current state at restore time would just read Wailo's own settings back, which is how a tool "restores" a machine into the broken state.
  - **The snapshot is written to disk before it is used.** A `SIGKILL` between apply and restore leaves a machine pointed at a listener that no longer exists, with nothing to say what it used to be. `recover()` runs at daemon start-up, before anything binds or dials, and the record survives until every service is verifiably back — a partial restore is retried on the next start rather than dropped.
  - **Forward through the proxy that was already configured.** The snapshot doubles as upstream discovery: whichever service was proxied becomes the `ProxyChain` route, so a corporate machine keeps working. Plain requests go to the upstream in absolute form; anything Wailo is not decrypting is carried by the upstream's own `CONNECT`.
  - **Applying is idempotent through the snapshot's presence.** A second apply is a no-op rather than a re-snapshot, because re-snapshotting over Wailo's own settings is precisely the trap above.
  - **A port change follows the machine.** `retarget` rewrites the port on the snapshotted services without touching the snapshot, so moving the listener does not silently leave the machine on the old port; and an internal restart (rebind, LAN toggle) closes the listener without restoring, so a rebind is not read as a stop.
  - **Stopping the proxy always restores, even with no listener.** `stop()` restores first and unconditionally. There is no ordering in which the machine is left pointed at nothing.
  - **It is session state, never persisted as an intent.** Unlike the port, the LAN bind, and the decrypt allowlist, "take over the system proxy" is not remembered across daemon restarts. A daemon that reclaimed it on start would take over a machine nobody asked it to that session — and the whole point of the disk record is the opposite, undoing a takeover rather than reinstating one.
- Alternatives considered:
  - **Refuse to apply when another proxy is configured:** rejected — that is exactly the population that most needs this to work, and it would push them back to editing settings by hand while Wailo silently does nothing.
  - **Overwrite and warn:** rejected — a warning does not restore the user's route, and the failure appears minutes later as "the internet is down" with no connection to the tool that caused it.
  - **Read a PAC file and evaluate it:** deferred — PAC needs a JavaScript engine and its own semantics for a case the explicit server setting already covers. A machine on PAC only gets the direct route today, which is a smaller gap than shipping a half-correct PAC evaluator.
  - **Set the proxy per-application instead of system-wide:** rejected — macOS has no such mechanism; per-app is `HTTP_PROXY` in an environment, which reaches CLIs and nothing a user actually browses in.
  - **Restore from a shutdown hook only:** rejected — a hook does not run on `SIGKILL`, a panic, or a power loss, and those are precisely the cases where a user is left with no network.
- Consequences:
  - `daemon` now edits OS network configuration. It is confined to one class, goes only through `networksetup`, and touches only the two proxy fields it snapshotted.
  - A machine behind a PAC-configured or authenticating proxy is not chained: the first is deferred above, the second would need credentials Wailo deliberately does not ask for. Both fall back to a direct route, which may fail — visibly, at the first request, rather than silently.
  - `ProxyServer` now has a second request-line form. Absolute form is used only on the hop to an upstream, so an origin still sees origin form and the `Host` header is unchanged either way.
  - macOS may prompt for authorisation the first time `networksetup` writes. That is the OS describing a real change and is not suppressed.
