---
adr: 0010
title: The iOS interceptor is a native Swift SDK (resolves ADR-0006)
date: "2026-07-08"
status: accepted
date_source: git-commit
---
# ADR-0010 — The iOS interceptor is a native Swift SDK (resolves ADR-0006)

- Status: Accepted. Supersedes the open question in ADR-0006.
- Context: ADR-0006 deferred the native-Swift vs Kotlin/Native-framework choice for iOS. Two facts now
  force it as iOS work starts (simulator-first): (a) `core` builds its transport on Ktor CIO, which has
  no Apple/Native target, so reusing `core` on iOS means swapping to the Ktor Darwin engine and shipping
  the Kotlin/Native runtime *inside every host app* — in direct tension with invariant #3 ("keep the SDK
  small and dependency-light; never depend on `engine`/`shared`"); (b) ADR-0009 already describes the iOS
  capture mechanism in native terms (`URLProtocol` + `URLSessionConfiguration` swizzling).
- Decision:
  - Build `sdk-ios` as a standalone **SwiftPM package**, isolated per invariant #3: it depends only on
    Foundation and the Wire **Swift** runtime — never on `core`, `engine`, `shared`, or `desktopApp`.
  - **Protobuf**: generate Swift from the *same* `protocol/**/*.proto` via the Wire compiler CLI, wired as
    a Gradle `JavaExec` task `:protocol:generateSwiftProto` (`com.squareup.wire.WireCompiler --swift_out`),
    since the Wire Gradle plugin emits only Kotlin/Java. Output is committed under
    `sdk-ios/Sources/WailoProtocol/generated`; the Swift `Wire` runtime is pinned to the same Wire version
    as `gradle/libs.versions.toml`. The DTOs stay generated from one schema (invariant #1, ADR-0002).
  - **Capture**: `WailoURLProtocol` (a `URLProtocol`) mirrors each URLSession HTTP(S) exchange into a
    Swift `CaptureSink` — the port-based model of ADR-0007, ported to Swift. It is installed globally two
    ways: `URLProtocol.registerClass` (covers `URLSession.shared`) plus swizzling
    `URLSessionConfiguration.default`/`.ephemeral` `protocolClasses` so sessions built by third-party
    libraries are captured with no cooperation — the iOS half of ADR-0009.
  - **Transport**: a Swift `WailoClient` reimplements the enqueue / background-drain / auto-reconnect /
    drop-oldest loop (ADR-0008) over `URLSessionWebSocketTask` (no Ktor), sending the identical binary
    `Envelope` frames the `engine` already decodes.
- Reachability (the "does iOS have `adb reverse`?" question): iOS has no `adb reverse`. The **Simulator**
  shares the Mac's network stack, so the default `localhost:8899` reaches the engine with zero config;
  **physical devices** use the Mac's LAN IP via `host`. USB tunneling (usbmux/PeerTalk) is deferred, in
  step with ADR-0004's deferred wifi/mDNS.
- Consequences:
  - The device-side *transport logic* now exists twice (Kotlin `core.WailoClient`, Swift `WailoClient`),
    a deliberate duplication accepted to keep the iOS SDK free of a Kotlin runtime. The *wire contract*
    is still single-sourced in `protocol`, so the two cannot drift on the format.
  - Module rule addition: `protocol (swift-gen) <- sdk-ios <- sample-ios`; `sdk-ios` still must not reach
    `engine`/`shared`/`desktopApp`.
  - Carries ADR-0009's documented blind spots: `httpBodyStream` request bodies are unreadable in
    `URLProtocol`, and WKWebView / background (`nsurlsessiond`) sessions are not covered.
  - Zero-touch auto-start before `main` (an ObjC `+load` installing the swizzle) is deferred; `Wailo.start()`
    is the explicit baseline, mirroring Android's manual `Wailo.interceptor()` baseline. Verified now by
    unit tests (protobuf round-trip), a Network.framework WebSocket loopback test (Hello + exchange stream
    and decode), and a runnable `sample-ios` capturing both `URLSession.shared` and a swizzled custom session.
