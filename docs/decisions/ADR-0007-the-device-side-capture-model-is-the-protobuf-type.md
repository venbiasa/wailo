---
adr: 0007
title: The device-side capture model is the protobuf type; delivery is a port
date: "2026-07-06"
status: accepted
date_source: git-commit
---
# ADR-0007 — The device-side capture model is the protobuf type; delivery is a port

- Context: M1 (the OkHttp interceptor) needs a representation of a captured exchange. A hand-written
  Kotlin model would drift from the M2 wire format and duplicate a DTO, which invariant #1 forbids.
- Decision: Define the capture messages (`HttpExchange`/`HttpRequest`/`HttpResponse`/`Header`) in
  `protocol` now and use the Wire-generated types directly as the device-side model. `core` exposes a
  single `CaptureSink` port; the platform interceptor knows nothing about where exchanges go.
  `sdk-android` provides the OkHttp `WailoInterceptor` plus a `LogcatSink` for M1 verification.
- Consequences: One representation from capture to wire, so M2 adds a WebSocket sink without touching
  the interceptor. Transport framing (envelope, hello) is intentionally *not* defined yet. OkHttp is
  `compileOnly` in `sdk-android` so the SDK never imposes a version on the host app (invariant #3).
