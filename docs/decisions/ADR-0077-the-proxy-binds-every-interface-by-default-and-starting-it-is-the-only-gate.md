---
adr: 0077
title: The proxy binds every interface by default; starting it is the only gate
date: "2026-08-22"
status: accepted
relations: supersedes ADR-0074's loopback default and keeps the rest of it; extends ADR-0070
---
# ADR-0077 — The proxy binds every interface by default; starting it is the only gate

- Status: Accepted; implemented as `DEFAULT_PROXY_LAN` in `daemon`, with the reach reported by `wailo-cli set_proxy` and stated on Studio's proxy switch rather than only on the bind row. Verify with `cd studio && ./gradlew :daemon:test`.
- Context: ADR-0074 made the wider bind opt-in and argued the risk belonged to a second, deliberate act. Living with it inverted the common case. The proxy exists precisely for the clients that cannot host the SDK, and the largest group of those — a physical phone, a tablet, a device someone else's app is running on — are not on this machine at all. A loopback default means the first thing a user does after turning the proxy on is discover it cannot see the device they turned it on for, then find a second switch. Meanwhile the "two acts" protection was thinner than it read: `proxyLan` persists, so for anyone who ever needed a phone the second act happened once, months ago, and every session since has been a wide bind arrived at by memory rather than by choice.
- Decision:
  - **The bind defaults to every interface; the proxy still defaults to off.** ADR-0074's real protection was never the bind switch, it was that nothing starts a listener on its own. That half is unchanged and is now the whole gate: a persisted `true` is a bind address waiting for a decision, and the decision is starting the proxy.
  - **The notice moves to where the exposure begins.** With the wide bind arriving by default, a warning attached only to the bind row is a warning most users will never see, because they will never touch that row. `set_proxy --on` reports `bind=` and says what a wide one means; Studio's proxy switch says it in its own help. The bind row keeps its explanation for the user who goes looking.
  - **Narrowing stays first-class and persists.** `set_proxy_lan --off` and the Studio switch keep the proxy to this machine, durably. The reversal is about which way the default points, not about removing the choice.
  - **The embedded default stays loopback.** `DaemonRuntime`'s parameter default is `false`, unlike the shipped `DEFAULT_PROXY_LAN`, because the callers that omit it are tests — and a test suite has no business opening a port to whatever network the machine is on, or triggering a firewall prompt on a developer's laptop.
- Alternatives considered:
  - **Keep loopback and make the device path easier to find:** rejected — the discoverability fix and the default are the same question asked twice. Every path to "use a phone" ends at the same switch, so the honest choice is whether it should already be on.
  - **Default wide only when a device has been seen before:** rejected — a bind address that depends on capture history is unpredictable, and "why is it loopback today" is a worse question than either fixed answer.
  - **Prompt on first start instead of choosing a default:** rejected — a prompt at the moment someone is trying to debug something is the kind users answer without reading, and it would fire in headless and CI runs that cannot answer at all.
  - **Bind wide but refuse peers until one is approved:** rejected — it is pairing (ADR-0060) for clients that cannot pair, which is exactly why ADR-0074 concluded the mitigation has to be the user knowing rather than a mechanism.
- Consequences:
  - A fresh install that starts the proxy is reachable by anything that can route to the machine, for as long as the proxy runs. That is the trade this ADR accepts, and stopping the proxy ends it.
  - macOS asks for its incoming-connection exception on the first start rather than on the first bind change, which is earlier and more visible — a fair description of what changed, so it is still not suppressed.
  - Existing installs are unaffected either way: `daemon.properties` already holds an explicit value, and an explicit `false` keeps narrowing them.
  - ADR-0074's remaining decisions stand — the wording rule, persistence, the rebind on change, and `bind=` in status. Only its first bullet is reversed.
