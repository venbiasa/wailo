---
adr: 0074
title: The proxy's LAN bind is opt-in, persisted, and named as an open relay
date: "2026-08-22"
status: accepted
relations: extends ADR-0070; the device-facing half of ADR-0071's setup story
---
# ADR-0074 — The proxy's LAN bind is opt-in, persisted, and named as an open relay

- Status: Accepted; implemented as a `lan` flag on `ProxyServer.start`, `ProxyController.setLan`, a persisted `proxyLan` setting, `wailo-cli set_proxy_lan`, and a Settings switch in Studio whose help text is where the open-relay warning is spelled out.
- Context: ADR-0070 bound the proxy to loopback and left "a physical device cannot reach it" as a known gap. Closing it is one line at the socket and a real decision everywhere else: a proxy on `0.0.0.0` will relay for anyone who can route to this machine. On a home network that is a phone; on a café network it is everyone in the café, using the user's IP, through a listener with no authentication. Wailo's capture socket solved the same problem with pairing (ADR-0060), but a proxy cannot — the whole point is that the client is something Wailo does not control and cannot ask to authenticate. So the mitigation has to be the user knowing, not a mechanism.
- Decision:
  - **Loopback is the default; the wider bind is a separate switch.** Starting the proxy never widens it, and widening it never starts the proxy. Two acts, because the risk belongs to the second one.
  - **Say what it is, in the surface that turns it on.** Every surface that offers this says the listener becomes usable by anything that can reach the machine, and suggests turning it off afterwards. A checkbox labelled only "Allow LAN" would be a setting the user cannot evaluate.
  - **The choice persists; the proxy still does not.** A device set up once should not need reconfiguring every session, so `proxyLan` is durable daemon configuration (ADR-0061's class). It only takes effect when something explicitly starts the proxy, so a persisted `true` cannot by itself put an open relay on the network.
  - **Changing it restarts a running listener.** A socket's bind address is fixed at `bind`, so the alternative is a switch that silently does nothing until the next start — the worst outcome for a security-relevant control, in both directions.
  - **Status reports the bind, not just the address.** `ProxyStatus.lan` is its own field rather than something inferred from the address, so a surface can warn even when the LAN address cannot be resolved, and `reachableAddress` gives every frontend and the CLI one answer to "what do I point the phone at".
- Alternatives considered:
  - **Bind to the LAN address instead of `0.0.0.0`:** rejected — a laptop changes networks and addresses, and a listener pinned to a stale one silently stops accepting. It also does not reduce exposure in any way that matters: reachable is reachable.
  - **Authenticate proxy clients (Proxy-Authorization):** rejected for now — the clients are browsers and third-party apps whose proxy configuration often cannot carry credentials, and a mechanism that half the clients cannot use would push users to turn it off rather than make them safer. Worth revisiting if a concrete client demands it.
  - **Restrict to the local subnet by peer address:** rejected — it reads as protection while giving almost none, since the untrusted case is usually another host on that same subnet.
  - **Make it session-only, never persisted:** rejected — it makes the daily device workflow a repeated act, and repeated safety prompts are the ones people learn to click through. Requiring an explicit start each session is the check that actually binds.
- Consequences:
  - A physical phone can now use the bundled proxy: point its Wi-Fi proxy at the reported address, and install the local root (ADR-0073) to decrypt anything unlocked.
  - macOS will ask for an incoming-connection firewall exception the first time the wider bind happens. That prompt is a fair description of what changed, so it is not suppressed.
  - `proxy_status` gains a `bind=` field, and `reachableAddress` replaces the assumption that a proxy is always `127.0.0.1`.
