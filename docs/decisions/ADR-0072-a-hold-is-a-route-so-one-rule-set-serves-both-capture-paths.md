---
adr: 0072
title: A hold carries a decision route, so one rule set serves both capture paths
date: "2026-08-22"
status: accepted
relations: extends ADR-0067 (the daemon decides every hold) and ADR-0070 (the proxy as a second capture path); keeps ADR-0033's precedence and ADR-0027/0019/0029's rule semantics
---
# ADR-0072 — A hold carries a decision route, so one rule set serves both capture paths

- Status: Accepted; implemented as `HoldRoute` + `holdExternal`/`releaseExternalHold` on `WailoEngine`, a `ProxyRules` seam in `:proxy`, and `HostProxyRules` in `daemon`. Verify with `cd studio && ./gradlew :proxy:test :daemon:test`.
- Context: ADR-0070 landed the proxy as a recorder. Every interception feature stayed SDK-only, and the reason is structural rather than incidental: a rule is *pushed to the device*, which matches it, holds its own request open, and raises a `BreakpointHit` the desktop answers over the same socket. A proxied exchange has no device. Nothing evaluates a rule for it, and nothing can be told to resume it — `WailoEngine` routed every decision by looking up the `DeviceConnection` that raised the hold. The user's ask was tool parity, which is really two questions: who matches the rules for a proxied exchange, and how does a decision reach a socket this process is holding open itself.
- Decision:
  - **A hold is keyed by a route, not by a session.** `pausedRoutes` maps a correlation id to either the device connection that raised it or a callback the relay thread is parked on. `resumeBreakpoint`/`abortBreakpoint` are unchanged for callers, so Studio, the CLI, MCP, and Seed spend answer a proxy hold with the code that already answers an SDK one, and `listHolds` is one queue.
  - **The daemon evaluates the rules for proxied traffic.** `HostProxyRules` reads the same `HeadlessHost` registries the device snapshots are compiled from, and applies ADR-0033's precedence unchanged: the Capture Filter decides what is kept; with no breakpoint a Map Local rule short-circuits; with one, the breakpoint owns the exchange and Map Local supplies the response it shows.
  - **`:proxy` learns the *shape* of interception, never its content.** A `ProxyRules` interface with three calls — an `Interception` pre-flight from method and URL, a request verdict, a response verdict — keeps the module off `engine` and `host` while letting the relay be tested against a fake. Verdicts speak in `protocol` types, which the module already depends on.
  - **A filtered exchange is still relayed and still interceptable.** The Capture Filter decides what is *recorded*, matching the device, where a filtered host still reaches the network. A proxy that dropped the connection instead would turn a display preference into an outage.
  - **A held body is read whole, capped at 32 MB.** This is the one place the relay gives up streaming, because an editor cannot offer half a payload. Past the cap the exchange is relayed unheld rather than failed: a rule that could not stop one request is recoverable, a broken page is not.
  - **A proxy abort is a visible `502`, and stopping the proxy releases every hold.** There is no second route to the origin, so the SDK's fail-open has no analogue: an abandoned exchange has to become an error the client can see.
- Alternatives considered:
  - **Share the matching code with the SDK:** rejected — `sdk-android` ships inside third-party apps and must not depend on anything in the studio build (AGENTS invariant 3). The duplication is real and is the price of that boundary; ADR-0070's consequence list now names it so the two copies are changed together.
  - **A separate proxy rule list:** rejected — the user's ask was that a rule they already wrote works, not that they write it twice. Two lists would also need their own precedence, UI, persistence, and CLI.
  - **Let the proxy resolve its own holds:** rejected — ADR-0067 put the decision on the daemon precisely so a headless session can answer one. A relay that decided for itself would be a second master, invisible to the CLI and MCP.
  - **Poll for the decision instead of parking the thread:** rejected — the connection is on a virtual thread whose entire purpose is to block cheaply, and a poll interval is latency added to every resume for no gain.
  - **Buffer held bodies to the spool and edit by range:** deferred — it is the honest fix for the 32 MB cap, but it needs the range-backed editor that is still outstanding, and a `PausedExchange` currently carries its bodies inline for every existing frontend.
- Consequences:
  - `PausedExchange` no longer implies a device: its `deviceName` is `Proxy` and its `appId` the client's address for a proxied hold. Anything rendering a hold has to tolerate that, and the Devices panel must not grow a row from it.
  - A response-phase hold on the proxy path delivers the body decoded, with `Content-Encoding` dropped and `Content-Length` restated. Re-compressing an edited body only to have the client inflate it would be work done to hide that the exchange was stopped.
  - A hold now blocks a real client socket. A breakpoint left waiting is a page that hangs, which is more visible than an SDK hold and is the intended feedback.
  - The Capture Filter is now evaluated in two places with the same semantics; `captureFilterAdmits` in `host` is the desktop copy and must stay byte-identical to the device matchers.
