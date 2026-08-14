---
adr: 0008
title: Transport framing (Envelope) and an interim desktop list before the M3 viewer
date: "2026-07-06"
status: accepted
date_source: git-commit
---
# ADR-0008 — Transport framing (Envelope) and an interim desktop list before the M3 viewer

- Context: M2 needs the on-wire framing for the WebSocket stream and a receiver. The immediate ask was
  only a live text list on the desktop, not the full M3 three-pane inspector.
- Decision:
  - Add `Envelope { oneof { Hello, HttpExchange } }` + `Hello` to `protocol`. Each WebSocket frame is
    one binary-encoded `Envelope`; the device opens with `Hello` (device/app/platform), then streams
    `exchange` envelopes. The server keys sessions off that `Hello`.
  - `core.WailoClient` (Ktor CIO client) implements `CaptureSink`: `onExchange` only enqueues, a
    background loop drains and auto-reconnects, and the buffer drops oldest on overflow so a slow or
    absent desktop can never block or OOM the host app.
  - `engine.WailoEngine` (Ktor CIO server) decodes envelopes into `CapturedExchange` rows and
    publishes them as a `StateFlow` — the UI-agnostic query surface (invariant #2).
  - Interim UI: `desktopApp` renders that `StateFlow` as a plain text list for now; the richer viewer
    lands in `shared` at M3, so `shared` stays a placeholder until then. `desktopApp` takes a
    *test-only* dependency on `core` to run the client↔server loopback integration test.
- Consequences: One framed message type; new message kinds are a oneof extension. Ktor client now ships
  in the Android SDK (size cost accepted); the port-based `CaptureSink` keeps an OkHttp-WebSocket or
  iOS swap open later. The desktop temporarily hosts UI that will move to `shared`.
