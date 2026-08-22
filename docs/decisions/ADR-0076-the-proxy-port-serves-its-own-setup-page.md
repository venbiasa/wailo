---
adr: 0076
title: The proxy port answers a browser with its own setup page, and never mints a root to do it
date: "2026-08-22"
status: accepted
relations: completes the device half of ADR-0074; constrained by ADR-0073 and ADR-0071
---
# ADR-0076 — The proxy port answers a browser with its own setup page, and never mints a root to do it

- Status: Accepted; implemented as a `ProxySetup` seam in `:proxy` and `ProxySetupPage` in `daemon`, served for origin-form `GET /`, `/setup`, and `/cert`. Verify with `cd studio && ./gradlew :daemon:test` (`ProxySetupPageTest`).
- Context: ADR-0074 let a phone reach the proxy, and ADR-0073 put the local root on the desktop — leaving the two ends of the setup story unconnected. A phone cannot read a file on a Mac. The alternatives users actually reach for are all bad: AirDrop (wrong platform half the time), email (a CA certificate through a mail provider), a cable, or a QR code of a 1 KB PEM. Meanwhile the device is already sending every request through Wailo, which makes the machine trivially reachable at an address the user has just been shown. Charles and Proxyman both solved this with a magic hostname the proxy intercepts (`chls.pro/ssl`, `proxy.man/ssl`). A request that arrives in origin-form is the same opportunity without owning a domain: something dialled the proxy port expecting a web server, which is never a request to relay — until now it was a `400`.
- Decision:
  - **Origin-form is the trigger, not a magic hostname.** A request-target with no scheme means the client browsed *to* this port rather than through it, so there is no origin and nothing to relay. Answering it costs nothing that worked before, needs no domain Wailo does not own, and the address is the one already printed in Settings and by `set_proxy_lan`. It works whether or not the device's proxy is configured yet, because a configured device's request for that address loops back to this same listener.
  - **The page never creates a root.** It reads the existing one or says where to make one. A device on the network must not be able to cause a universal signing key to appear on the user's machine — that stays an act at the desk (ADR-0073), and it is the one property of this feature worth a test of its own.
  - **`daemon` owns the content; `:proxy` owns only the seam.** `ProxySetup` returns a response or null, and `:proxy` keeps its `400` when it is null. The relay module has never heard of a certificate and does not start now (ADR-0070).
  - **Serve it as `application/x-x509-ca-cert`.** That MIME type is what makes iOS treat the download as a profile to install and Android open its certificate installer; as `text/plain` both platforms download a file and do nothing with it. The page then spells out the second, non-obvious step on each platform — iOS's Certificate Trust Settings toggle, Android's user-CA caveat — because a certificate that is installed but untrusted looks exactly like Wailo failing.
  - **The page is not recorded.** A page Wailo serves about itself is not traffic the user came to inspect, and a row for it in the list would be noise at exactly the moment the user is looking for their app's first request.
- Alternatives considered:
  - **A magic hostname (`wailo.local`, `wailo.proxy`):** rejected — it needs either a domain Wailo owns or a name that may become a real TLD, and it only works once the device's proxy is already configured. The port answers in both states.
  - **Serve it on a second HTTP port:** rejected — another listener, another firewall prompt, another number in the UI, for something the existing port can answer.
  - **A QR code in Studio holding the PEM:** rejected — a 1 KB payload makes a dense code, and it still leaves the user to get an installable file out of a scanner. A QR of the *URL* is worth adding later; the page it points at has to exist either way.
  - **Mint on demand when the page is fetched:** rejected outright. Convenient, and it would let anything that can reach the port create the most dangerous key on the machine.
- Consequences:
  - Device setup is: point the Wi-Fi proxy at the address, open it in a browser, install, trust, unlock the hosts. No file transfer.
  - With the LAN bind on, anyone who can reach the port can fetch the public certificate and learn the machine runs Wailo. The certificate is public by construction and trusting it is a deliberate act on the fetching device, so this discloses presence, not capability — and ADR-0074 already asks the user to weigh that network.
  - `:proxy` gains a fourth seam beside `ProxyRules`, `ProxyTls`, and `ProxyChain`. All four are the same shape: the relay asks, the daemon decides.
