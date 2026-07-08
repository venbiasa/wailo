# Decisions (ADR log)

Append-only. Newest at the bottom. Each entry: context, decision, consequences.

## ADR-0001: In-app SDK interception instead of a system proxy

- Context: The reference experience (a proxy debugger) requires certificate install and device-wide
  proxy config. We want lower-friction capture and to run in automation/CI.
- Decision: Capture inside the app via a native interceptor (OkHttp on Android; URLProtocol/URLSession
  on iOS), streaming to a desktop app.
- Consequences: No cert/proxy setup. Only traffic through the instrumented client is captured (e.g.
  OkHttp), not arbitrary system traffic. Requires integrating the SDK into the target app.

## ADR-0002: Protobuf (Square Wire) as the wire protocol

- Context: Kotlin and Swift both need the same message types; traffic volume can be high.
- Decision: Define messages once in `protocol/**/*.proto`; generate with Wire (Kotlin now, Swift later).
- Consequences: Compact, evolvable, no cross-language model drift. Adds a codegen step. JSON rejected
  for drift risk and payload size.

## ADR-0003: Headless engine separate from the UI

- Context: Roadmap includes headless automation (Appium) and MCP, not just the desktop UI.
- Decision: `engine` owns transport + capture store + query/command API and is UI-agnostic. UI, CLI,
  and MCP are thin frontends.
- Consequences: Automation/MCP are additive, not forks. Slightly more indirection for the desktop app.

## ADR-0004: WebSocket over adb reverse for the first transport

- Context: Need a simple, reliable device->desktop channel for Android during early development.
- Decision: WebSocket with device as client, desktop as server on :8899, forwarded via `adb reverse`
  per device. mDNS/wifi discovery and iOS transport deferred.
- Consequences: Zero network config for USB/adb-connected devices; multi-device supported by running
  the reverse per serial. Devices not adb-reachable are out of scope until wifi/mDNS lands.

## ADR-0005: Follow current KMP-wizard conventions, add sibling modules

- Context: Want familiar, maintainable structure, but the wizard emits a single-app project while
  Wailo needs an isolated SDK, an engine, and samples.
- Decision: Adopt the wizard's toolchain and conventions (version catalog with plugin aliases,
  `com.android.kotlin.multiplatform.library` DSL, foojay, `shared` + `desktopApp` naming) and add
  `protocol`, `core`, `sdk-android`, `engine`, `sample-android` as sibling modules.
- Consequences: Known-good toolchain matrix; standard layout; the SDK stays decoupled from the UI.

## ADR-0006: iOS core-sharing decision deferred

- Context: iOS interceptor must be native; only `core` could be shared, via Kotlin/Native.
- Decision: Build Android first with `core` in KMP; keep `core`'s surface small and port-based; decide
  native-Swift vs Kotlin/Native-framework for iOS when iOS work starts.
- Consequences: No premature commitment; protobuf already carries the contract cross-language.

## ADR-0007: The device-side capture model is the protobuf type; delivery is a port

- Context: M1 (the OkHttp interceptor) needs a representation of a captured exchange. A hand-written
  Kotlin model would drift from the M2 wire format and duplicate a DTO, which invariant #1 forbids.
- Decision: Define the capture messages (`HttpExchange`/`HttpRequest`/`HttpResponse`/`Header`) in
  `protocol` now and use the Wire-generated types directly as the device-side model. `core` exposes a
  single `CaptureSink` port; the platform interceptor knows nothing about where exchanges go.
  `sdk-android` provides the OkHttp `WailoInterceptor` plus a `LogcatSink` for M1 verification.
- Consequences: One representation from capture to wire, so M2 adds a WebSocket sink without touching
  the interceptor. Transport framing (envelope, hello) is intentionally *not* defined yet. OkHttp is
  `compileOnly` in `sdk-android` so the SDK never imposes a version on the host app (invariant #3).

## ADR-0008: Transport framing (Envelope) and an interim desktop list before the M3 viewer

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

## ADR-0009: Capture third-party and non-OkHttp traffic via auto-instrumentation (build-time ASM on Android, swizzle on iOS)

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

## ADR-0010: The iOS interceptor is a native Swift SDK (resolves ADR-0006)

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

## ADR-0011: Fold `core` into `sdk-android`; drop device-side Kotlin Multiplatform (amends invariant #4)

- Status: Accepted. Amends ADR-0010's consequence (the Kotlin transport moves from `core` into
  `sdk-android`) and rewrites AGENTS invariant #4.
- Context: `core` existed as a separate **Kotlin Multiplatform** module (jvm + android targets) for one
  reason — invariant #4 / ADR-0006 kept it thin and port-based so an *iOS* strategy could reuse it via
  Kotlin/Native. ADR-0010 chose a native-Swift iOS SDK instead, so nothing will ever share `core`'s
  Kotlin. Post-ADR-0010 the module earned nothing: its only production consumer was `sdk-android`
  (`api(projects.core)`), its only other use was a `desktopApp` test that drove `core.WailoClient`
  against the `engine` server. A whole KMP module (with an Android target and a `commonMain` indirection)
  for two Kotlin files (`CaptureSink`, `WailoClient`) consumed on one platform is pure overhead.
- Decision:
  - Move `CaptureSink` and `WailoClient` into `sdk-android` (package `com.venbiasa.wailo.sdk.android`)
    and delete the `core` module. `sdk-android` absorbs `core`'s formerly-transitive deps directly
    (coroutines + Ktor CIO client); it already shipped them transitively, so the SDK gains no new weight
    (invariant #3 intact). `protocol` becomes `api` on `sdk-android` because `HttpExchange` is exposed
    through the public `CaptureSink`.
  - The `desktopApp` loopback test is split to where each half now lives, since `sdk-android` (Android)
    and `engine` (JVM) can no longer meet in one test without `sdk-*` reaching `engine` (invariant #3):
    `engine` gets a server-side loopback test (a raw Ktor client frames `Envelope`s at the real server),
    and `sdk-android` gets a client-side test (the real `WailoClient` streams to a throwaway Ktor server,
    used as a test fixture only — never the `engine` module).
  - No shared device-side Kotlin module remains. Invariant #4 is rewritten: device transport/sink logic
    lives *inside* `sdk-android`; `sdk-ios` reimplements it in Swift; the two stay consistent only via the
    `protocol` wire contract.
- Scope / what stays: `protocol` and `shared` remain KMP — they back the shared Compose desktop UI
  (`shared` holds shared UI/logic, `desktopApp` is the desktop launcher), and `shared.commonMain`
  depends on `protocol`, so `protocol` must keep a common target. Only the *device-side* KMP is removed.
  `engine` and `sdk-android` are single-target. `core → sdk-android` was the only viable merge:
  `protocol` is the cross-language contract (also feeds `sdk-ios`), and `engine` must stay headless and
  separate from the SDK (invariants #1, #2).
- Consequences:
  - Module count drops by one; the device side has zero KMP modules and no `commonMain` indirection.
  - `WailoClient`/`CaptureSink` are now Android-module public API (renamed package); this is an early-M2
    surface change, acceptable pre-1.0.
  - Verified: `:sdk-android:testDebugUnitTest` (WailoClient loopback) + `:engine:test` (server loopback)
    pass, and `:sample-android:assembleDebug` still builds with ASM auto-instrumentation.

## ADR-0012: Sample matrix — `sample-android`, `sample-ios` (real app + CLI harness), and a planned `sample-kmp`

- Status: Accepted and built. `sample-ios` (SwiftUI app + CLI harness) and `sample-kmp` (shared Kotlin +
  Android app + iOS app shell) both exist and compile; only the on-device/simulator *runtime* against a
  live desktop remains a manual check (see "Verified").
- Context: a consumer adopts Wailo in one of three ways, and each deserves a runnable sample so the
  integration is dogfooded rather than assumed:
  1. native Android (Kotlin) → `sdk-android`;
  2. native iOS (Swift/SwiftUI) → `sdk-ios`;
  3. Kotlin Multiplatform (shared Kotlin + native shells) → `sdk-android` on Android, `sdk-ios` on iOS.
  `sample-ios` was initially a macOS command-line executable (chosen for headless/CI verifiability
  when no simulator was available); it exercised the real SDK code but never ran inside an app.
- Decision:
  - **`sample-ios` is a real SwiftUI iOS app**, with the former executable kept as a **CLI smoke
    harness** under `sample-ios/cli/`. Both consume `WailoSDK` from the local `sdk-ios` package and make
    the identical `Wailo.start()` call. The app project is generated by **XcodeGen** from a committed
    `project.yml` (the `.xcodeproj` is generated and gitignored); the CLI stays a SwiftPM package so it
    runs headlessly in CI (`swift run`). The app displays the requests it fires; capture is proven by the
    desktop inspector + `ConsoleSink` (the SDK's captured stream is intentionally not exposed in-app —
    `WailoURLProtocol.sink` stays internal, no SDK API was added for the sample).
  - **`sample-kmp`**: a Kotlin Multiplatform app — `sample-kmp/shared` does its networking via **Ktor**
    (OkHttp engine on Android, Darwin engine on iOS) and has **no Wailo reference**. It validates and
    documents the load-bearing principle: **Wailo is integrated at the platform shell, never in
    `commonMain`.** There is deliberately **no unified `commonMain` Wailo API**, and there can't be one:
    `sdk-ios` is a native Swift package and Kotlin/Native can't cleanly consume it (ADR-0010). So the two
    SDKs enter at different layers:
      - **`sdk-android` is a first-class part of the KMP build.** `sample-kmp/androidApp` applies the
        `wailo-gradle-plugin` and depends on `sdk-android`; the plugin's `ALL`-scope rewrite catches the
        `OkHttpClient.build()` *inside Ktor's OkHttp engine* (a dependency), so `shared`'s traffic is
        captured with zero shared-code changes — the app only installs a sink at startup.
      - **`sdk-ios` never touches the KMP build.** `sample-kmp/iosApp` is an Xcode app that links the
        KMP `Shared` framework *and* the `WailoSDK` SwiftPM package, and calls `Wailo.start()` in Swift.
        The Darwin engine sits on `URLSession`, so the swizzle captures `shared`'s traffic — again with
        zero shared-code changes.
    Same shared Kotlin, captured on both platforms, wired only at each shell.
  - Why `sample-kmp` is worth the scaffolding: it proves the highest-risk path transparently. On iOS,
    Ktor's **Darwin engine sits on `URLSession`**, so `sdk-ios`'s `URLProtocol` swizzle captures Ktor
    traffic with zero shared-code cooperation; on Android, Ktor's **OkHttp engine builds an
    `OkHttpClient`**, which the ASM plugin's `build()` rewrite already catches. A KMP shop gets capture
    on both platforms without touching shared code.
  - Caveat the sample must probe: Ktor Darwin can be handed a fully custom `URLSessionConfiguration`
    via `configureSession {}`; a config not derived from `.default`/`.ephemeral` can slip past the
    swizzle (`URLSession.shared` is still covered by `registerClass`). This is the iOS blind-spot from
    ADR-0009 surfacing in a KMP context.
- Module rules: `sample-*` are leaf consumers and must not reach `engine`, the desktop `shared`, or
  `desktopApp`. `sample-kmp:androidApp` depends on `sample-kmp:shared` + `sdk-android` (and applies the
  plugin); `sample-kmp:shared` is a distinct KMP module from the desktop `shared`. The iOS shell
  integrates `sdk-ios` at the Xcode/SwiftPM level (not via Gradle/KMP), consistent with ADR-0010.
- Verified: `:sample-kmp:androidApp:assembleDebug` builds (the ASM `transformDebugClassesWithAsm` step
  runs over the app + Ktor's OkHttp engine); `:sample-kmp:shared:linkDebugFrameworkIosSimulatorArm64`
  compiles the Darwin `actual` and produces the `Shared` framework; and `xcodebuild … -sdk
  iphonesimulator` on `sample-kmp/iosApp` compiles+links the app against both `Shared` and `WailoSDK`,
  producing `WailoKmpSampleiOS.app`. What remains a *manual* check (per the repo's two-tier verification)
  is the on-simulator/device run streaming to a live desktop — do it alongside the M3/M4 desktop work.
- Consequences:
  - Verified now: the SwiftUI `sample-ios` compiles for the iOS Simulator SDK (`xcodebuild … -sdk
    iphonesimulator`), and the `sample-ios/cli` harness still builds (`swift build`).
  - New tooling dependency for the iOS samples: **XcodeGen** (`brew install xcodegen`), documented in the
    README. The `.xcodeproj` is regenerated, not committed.
  - `ktor-client-darwin` was added to the version catalog for `sample-kmp/shared`'s iOS engine; building
    the KMP iOS target pulls the Kotlin/Native toolchain (`~/.konan`) on first run.
