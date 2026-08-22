---
adr: 0070
title: The proxy is an additive, daemon-owned capture source in a bundled `:proxy` module
date: "2026-08-22"
status: accepted
relations: extends ADR-0001 (adds a second capture path without replacing the SDK); extends ADR-0058 (daemon owns it, like every other listener); depends on ADR-0069
---
# ADR-0070 — The proxy is an additive, daemon-owned capture source in a bundled `:proxy` module

- Status: Accepted; implemented as `studio/proxy` (`ProxyServer`), adapted to the engine by `daemon` (`EngineProxyCaptureSink`), controlled over the daemon's loopback API from Studio's Settings panel, `wailo-cli set_proxy`, and the menu bar.
- Context: ADR-0001 chose in-app SDK interception *instead of* a system proxy, and that reasoning still holds for the primary path: an SDK needs no certificate, no OS setting, and survives pinning because it sits above TLS. But it only reaches apps that ship the SDK. A developer debugging a third-party app, a device they cannot rebuild, a CLI tool, a desktop browser, or a webhook from a service they do not own has nothing to instrument. Every competing tool answers that with a proxy, and the reason they can is that a proxy needs nothing from the target. The question this ADR settles is not whether to add one — the user asked for it — but whose process owns it and what it is allowed to assume.
- Decision:
  - **Additive, not a replacement.** The SDK stays the primary, zero-setup path and keeps its own ADR-0001 rationale. Proxying is off in a fresh daemon and only starts when a human explicitly starts it. Nothing about the SDK path changes when it is running.
  - **The daemon owns the listener.** It is a socket that captures traffic, which ADR-0058 already reserves to the daemon: a frontend that bound it would split state the moment a second frontend opened, and a proxy that died with a closed window would take a browser's network with it. Studio, CLI, MCP, and the menu bar are clients of a `set_proxy_enabled` command, exactly as they are for every other master.
  - **A separate `:proxy` module, not code inside `daemon`.** It depends on `wailo-protocol` and the JDK, never on Compose, `engine`, or `host`, so the protocol translation is testable without standing up capture and the HTTP machinery cannot quietly grow a dependency on engine state. `daemon` is the only thing that adapts it: `:proxy` hands over an `HttpExchange` plus body handles, `daemon` turns those into a `CapturedExchange`.
  - **Proxy rows are born in the daemon, so the source marker is not on the wire.** `CapturedExchange` gains a `CaptureSource` (`SDK` or `PROXY`) and the daemon's DTO carries it; `protocol` is untouched. A proxy row never travels the device wire, so putting the field there would add a cross-build protobuf change to describe something no device will ever send.
  - **Bodies stream to the spool as they are relayed.** The proxy tees each direction: the client and the origin see the original framing byte for byte while the entity body goes to the `BodyStore` from ADR-0069, decompressed for inspection. There is no per-body cap — that is the whole reason the spool had to exist first.
  - **One connection per virtual thread, blocking IO.** The daemon already runs `Executors.newVirtualThreadPerTaskExecutor()`, and a proxy is the canonical shape a virtual thread was designed for: mostly parked on a socket, occasionally copying bytes.
- Alternatives considered:
  - **Bind the proxy in Studio:** rejected — it would make a browser's connectivity depend on a window being open, and it contradicts ADR-0058's single owner. A headless MCP or CLI session could not use it at all.
  - **A second daemon process for proxying:** rejected — two processes would each need their own capture state, and merging SDK and proxy traffic into one timeline is the point. It would also double the lifecycle, trust, and handshake surface.
  - **Put the HTTP code directly in `daemon`:** rejected — `daemon` is already the largest module and owns lifecycle, RPC, adb, usbmux, and settings. A proxy is a protocol implementation with its own test surface, and giving it a module is what keeps it from reaching into engine internals.
  - **Build on Ktor's server/client:** rejected for the core relay — a proxy needs byte-exact framing, `CONNECT` hijacking, and the ability to forward a malformed message rather than reject it, which a request/response framework actively works against. Raw sockets on virtual threads are less code and fewer surprises.
  - **A `source` field on the protobuf `HttpExchange`:** rejected — it would push a change through the consumer-pinned SDK build (ADR-0015) and republish `wailo-protocol` to describe a value only the desktop ever sets.
  - **Treat proxy clients as paired SDK devices:** rejected — pairing exists to authenticate a device that dialled in over the network (ADR-0060). A proxy client is identified by its socket, and pretending otherwise would put fake rows in the Devices panel.
- Consequences:
  - Proxy traffic is captured but is not yet subject to Capture Filter, Map Local, Breakpoints, or Seeds; those rules are still evaluated on the device. Bringing them across is its own milestone and will need holds to become source-neutral.
  - The control protocol moved to version 8 for the proxy state in the poll and the source on each row, so a frontend from before this change cannot read a daemon after it.
  - The daemon now binds a second port when proxying, so a port conflict is a new, visible failure mode; the error is reported through the same status the panel renders rather than only to the log.
  - Anything the proxy cannot parse as HTTP/1.1 is a failure the client sees directly. Unlike the SDK, a proxy cannot fall open — the request is only reaching the network through us.
  - The daemon's idle exit has to account for a running proxy: a browser configured to point at a daemon that exited would lose its network, so proxying holds the daemon up for as long as it is on.
