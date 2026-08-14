---
adr: 0009
title: Capture third-party and non-OkHttp traffic via auto-instrumentation (build-time ASM on Android, swizzle on iOS)
date: "2026-07-06"
status: accepted
date_source: git-commit
---
# ADR-0009 — Capture third-party and non-OkHttp traffic via auto-instrumentation (build-time ASM on Android, swizzle on iOS)

- Status: Accepted (locked). Android build-time ASM is being de-risked now via a `wailo-gradle-plugin`
  proof-of-concept (`OkHttpClient.Builder.build()` call-site rewriting + a `WailoRuntime` hook); the iOS
  `+load` swizzle stays deferred until iOS work starts.
- Context: ADR-0001 accepted that only traffic through the instrumented client is captured, and ADR-0007
  wires that client manually (`Wailo.interceptor()`). Manual wiring only reaches HTTP clients the app
  itself constructs, so third-party libraries/SDKs that build their own OkHttp client (or use another
  stack) are invisible. We want capture that (a) reaches third-party libraries without their cooperation,
  (b) begins at process launch with no user action, and (c) keeps ADR-0001's promise: no device proxy, no
  certificate install, no MITM/TLS termination.
- Decision:
  - Keep `Wailo.interceptor()` as the simple, production-safe *baseline* for app-owned clients. Auto-
    instrumentation is additive on top of it, not a replacement.
  - The strategy on both platforms is the same principle: hook the *shared networking framework* the third
    party depends on, installed before the first request — never the library or its construction site.
  - Android: a build-time bytecode-instrumentation **Gradle plugin** (AGP ASM Instrumentation API,
    `InstrumentationScope.ALL`) that auto-wraps OkHttp (`OkHttpClient$Builder.build()` call sites) and,
    opt-in, `HttpURLConnection` (`URL.openConnection()`), routing exchanges into the existing `CaptureSink`.
    Hooks are woven at compile time, so they exist from the first instruction; a startup `ContentProvider`
    initializes a process-global default sink, and the `WailoClient` buffer (ADR-0008) covers exchanges
    captured before the desktop connects.
  - iOS: a native `sdk-ios` that from `+load` (before `main`) installs a global `URLProtocol` by swizzling
    `URLSessionConfiguration` (and/or swizzles the `URLSession` task/delegate API for higher fidelity), so
    third-party URLSession-based libraries are captured without cooperation, emitting the same protobuf
    `HttpExchange` (ADR-0002/0006).
  - Below the HTTP framework (native/Flutter/gRPC/`Network.framework`), a TLS-hook tier (bytehook on
    Android, fishhook on iOS, tapping `SSL_read`/`SSL_write`) is a debug-only escape hatch, added only if a
    concrete need arises.
- Alternatives considered:
  - Android runtime **JVMTI self-attach** (ART TI exit-hooks, the mechanism behind the Android Studio
    Network Inspector), self-attached from a `ContentProvider` via `Debug.attachJvmtiAgent`: also meets
    "from launch, no user action", but rejected as the default because it is debuggable-only, needs API 28+
    (minSdk is 24), and ships a native agent. Build-time ASM works on any build/API with no native agent.
    JVMTI remains a fallback for dev-only scenarios where a build-time plugin is undesirable.
  - **Below-HTTP MITM** (VpnService/local proxy + a per-app `network_security_config` CA): rejected for the
    primary path — it reintroduces certificate and pinning fragility, i.e. no better than an external proxy,
    which is exactly what ADR-0001 set out to avoid.
- Consequences:
  - Third-party and framework-level traffic is captured with no per-client wiring and no proxy/cert, and
    capture begins at app start. These are additive capture *sources*; the `CaptureSink` port and the
    protobuf wire contract are unchanged (invariants #1/#2).
  - Auto-instrumentation implies a *process-global* default sink — a deliberate departure from today's
    explicit per-client wiring. The Android host app gains a build-time plugin; the SDK runtime stays thin
    (heavy logic is build-time, no native agent), preserving invariant #3.
  - Documented blind spots and caveats: WebView (WKWebView networking is out-of-process) and background
    sessions (`nsurlsessiond`); shaded/relocated OkHttp needs a configurable package allowlist; iOS
    `URLProtocol` loses request bodies sent via `httpBodyStream` and cannot see background sessions unless
    the task/delegate route is used.
  - Streaming/non-request-response traffic (WebSocket, gRPC, SSE) surfaced by these sources may require new
    `protocol` message kinds when implemented — schema evolves in `protocol`, never as device-side DTOs
    (invariant #1).
  - This ADR fixes the *approach*, not the schedule. A `wailo-gradle-plugin` POC now proves the Android
    ASM mechanism end-to-end (call-site rewrite + `WailoRuntime.hook` + idempotent injection). Hardening
    into a shipped milestone (shaded-OkHttp allowlist, `HttpURLConnection`/Cronet, startup `ContentProvider`
    default sink) and the iOS SDK remain in the roadmap "Later" phase, after the M3/M4 desktop work.
