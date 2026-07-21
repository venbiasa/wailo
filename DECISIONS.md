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

## ADR-0013: The M3 desktop viewer (overview) — where it lives, its seam, and its look

- Status: Accepted and built (`:shared:build` + `:desktopApp:build` green; formatting/JSON logic unit-tested
  in `:shared:jvmTest`). Realizes the "richer viewer lands in `shared` at M3" promise from ADR-0008 and
  **supersedes that ADR's interim plain-text list**, which is now removed.
- Context: the first real inspector UI — a live, tailing request list (newest at the bottom, ordered by
  time) over a request/response detail panel. ADR-0008 left `shared` a placeholder and rendered a throwaway
  text list in `desktopApp`. This turns `shared` into the actual viewer and `desktopApp` back into a thin
  host, per ADR-0003 (UI is a frontend over the headless `engine`).
- Decision:
  - **Placement.** The viewer is Compose Multiplatform in `shared`; `desktopApp` only owns the
    `WailoEngine`, maps its rows, and hosts the window calling `WailoApp(entries)`. Module arrows now match
    the documented graph: `protocol <- shared <- desktopApp`, and `shared` still must **not** reach
    `engine` (they are siblings under `desktopApp`).
  - **Seam.** `shared.FlowEntry` = session identity (`device/app/platform`) + the protobuf `HttpExchange`.
    It mirrors `engine.CapturedExchange` **by value** because the firewall forbids `shared -> engine`; the
    map happens at the `desktopApp` boundary. This is the same "duplicate across a firewall, single-source
    the wire contract" trade-off ADR-0011 accepted for the device SDKs. The list keys off the exchange's
    UUID (`id`), which is stable across `StateFlow` emissions, so `LazyColumn` recomposition stays cheap.
  - **Pure logic is the tested unit.** JSON pretty-printing, content-type/binary detection, and
    status/method/URL/bytes/wall-clock formatting are pure functions in `shared` commonMain, tested from
    `jvmTest` (kept out of `commonTest` so no Android host-test wiring is needed for a desktop-only UI).
    Wall-clock formatting is pure (commonMain has no `java.time`); the host passes its zone offset in.
  - **UX.** One merged list showing app/device per row (session sidebar deferred); newest pinned to the
    bottom with **smart auto-follow** — tailing pauses when the user scrolls up and a "jump to latest"
    control resumes it. Selecting a row opens a **resizable** bottom panel with **Request/Response tabs**
    (headers table + a body viewer that pretty-prints JSON, shows raw text otherwise, and labels
    empty/binary/truncated payloads). Monospace is used only for payloads and the tabular columns; all
    chrome stays Noto Sans.
  - **Look = the personal design system.** Material 3 + the grayscale tokens + **Noto Sans**, light/dark.
    `theme/Color.kt`, `Type.kt`, `Theme.kt` are generated from `design-tokens/tokens.json` and marked
    `DO NOT EDIT`. Status/intent colors (success/warning/info) have no M3 `ColorScheme` slot, so they ride
    a `WailoColors` `CompositionLocal`; everything else uses `MaterialTheme.colorScheme`.
- Consequences / trade-offs:
  - `desktopApp` now depends on `shared` (was `engine`-only + a stale test-only `core` dep pre-ADR-0011).
    The viewer owns its Material 3 theme; the old `desktopApp` Material 2 usage (bundled by
    `compose.desktop.currentOs`) is gone from our code.
  - **Noto Sans ships as one committed variable font** (`shared/src/commonMain/composeResources/font/`,
    ~2 MB); true Medium/SemiBold/Bold come from `FontVariation` weight axes (Compose ≥1.8; we're on 1.11).
    Variable-font weights need Android API 26+ at runtime — a non-issue while the UI is desktop-only, noted
    should `shared`'s UI ever run on Android.
  - The new AGP KMP library plugin needs `androidResources.enable = true` for the font to package on the
    Android target (which compiles but has no consumer yet).
  - We keep the `compose.*` dependency shortcuts (which emit deprecation warnings) rather than catalog
    GAVs: `material3`/`components-resources` have no plain `org.jetbrains.compose.*:*:<version>` coordinate,
    so the plugin must resolve them.
  - Verified by machine: `:shared:build` (JVM + Android compile, `:shared:jvmTest` covering the JSON/format
    logic) and `:desktopApp:build`. Live behavior — streaming, tail/auto-follow, selection, tab/body
    rendering, light/dark — is a manual smoke check under the repo's two-tier verification.

## ADR-0014: Pin the toolchain to the target consumer app (Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.2.21)

- Status: Accepted and built (full JVM/Android graph compiles, the KMP iOS framework links, and all unit
  tests + the plugin ASM test pass). Retargets the whole repo's build toolchain and rewrites the
  "Toolchain" section of AGENTS.md.
- Context: the first external consumer of the published SDK + `wailo-gradle-plugin` is a production app
  pinned to **Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21, JVM 21**. Wailo was on a
  bleeding-edge matrix (Kotlin 2.3.21, AGP 9.0.1, Gradle 9.3.1). That is not consumable there on three
  counts: (a) a Gradle plugin compiled against the AGP 9 / Gradle 9 APIs can't apply in an AGP 8.10 /
  Gradle 8.11 build; (b) Kotlin 2.3-compiled library metadata isn't readable by a Kotlin 2.2 compiler;
  (c) the KMP compatibility matrix caps Kotlin 2.2.21 at Gradle ≤8.14 / AGP ≤8.11.1. JVM already matched
  at 21. "Match the consumer app for now" is a compatibility pin to unblock integration, not a permanent
  stance.
- Decision: pin the entire Wailo build to the consumer app's toolchain — Gradle 8.11.1 (wrapper), AGP 8.10.1,
  Kotlin 2.2.21, JVM 21. Keep most of the stack: **Compose Multiplatform 1.11.0 stays** (it supports
  Kotlin 2.2 for JVM/Android targets; only its native/web targets need Kotlin 2.3, and the desktop stack
  is JVM+Android only), as do Wire 5.x and coroutines. **Ktor is the one dependency that had to move**
  (see the last bullet). The AGP 9→8 fallout was otherwise mechanical, not architectural:
  - Re-apply `org.jetbrains.kotlin.android` to the Android modules (`sdk-android`, `sample-android`,
    `sample-kmp:androidApp`). AGP 9's built-in Kotlin support does not exist in AGP 8.10.
  - Move `jvmTarget` out of `androidLibrary { compilerOptions { } }` (an AGP-9-only DSL) to a task-level
    `tasks.withType<KotlinJvmCompile>` config in the KMP modules (`protocol`, `shared`,
    `sample-kmp:shared`), keeping every JVM/Android compilation on target 21.
  - Drop `androidResources { enable = true }` from `shared`: that DSL is AGP 8.11+. The Noto Sans font is
    a Compose Multiplatform resource (`composeResources/`) that the Compose resources plugin packages
    itself, so it does not need AGP's android-res processing. This **amends ADR-0013's consequence** that
    claimed the font needs the `androidResources` opt-in on the Android target.
  - Pin **Ktor 3.4.3 → 3.3.3**. On the JVM/Android graph Ktor 3.4.3 was fine (JVM Kotlin metadata is
    lenient), but Kotlin/Native is strict: Ktor 3.4.x is built with Kotlin 2.3, so its iOS klibs carry
    KLIB ABI 2.3.0 and Kotlin/Native 2.2.21 refuses them — `sample-kmp:shared`'s iOS target could not
    resolve `ktor-client-darwin`. Ktor 3.3.x is the last line built on Kotlin 2.2, and the client/server
    APIs used here are unchanged across 3.3/3.4. This also moves the Ktor version bundled in the shipped
    `sdk-android` — a benign minor downgrade.
- Consequences:
  - The published `wailo-protocol`, `wailo-android`, and `com.venbiasa.wailo` plugin artifacts are now
    consumable by any Gradle 7.6.3–8.14 / AGP 7.3.1–8.11.1 / Kotlin ≥2.2 build — the target app included.
  - Deviates from the previously-documented bleeding-edge toolchain; this is a deliberate downgrade under
    an ADR (per invariant policy). Revisit when the target app upgrades, or if the desktop stack ever needs
    a Kotlin-2.3-only Compose feature.
  - `shared`'s Android target compiles but ships without AGP-processed android resources (it still has no
    consumer; the desktop UI runs on the JVM target). If `shared`'s UI ever runs on Android, re-add the
    resources opt-in — which needs AGP ≥8.11.
  - The Gradle wrapper's `distributionSha256Sum` was dropped during the downgrade (the pinned 9.3.1 hash no
    longer applied); re-pin it if distribution verification is wanted.
  - The `wailo-gradle-plugin` ASM instrumentation (AGP Instrumentation API) needed no source change — it
    already uses only APIs stable across AGP 8.10 and 9.

## ADR-0015: Split the repo into two builds — consumer-pinned SDK + a modern `studio` desktop build

- Status: Accepted and built (root SDK build green incl. the iOS frameworks; the new `studio` build
  compiles + tests on the modern toolchain, resolving `wailo-protocol` from Maven Local). Adds a second
  Gradle build, rewrites the module-graph/toolchain sections of AGENTS.md, and scopes ADR-0014's pin to the
  SDK build only.
- Context: ADR-0014 pinned the *entire* repo to the consumer app's toolchain (Gradle 8.11.1 / AGP 8.10.1 /
  Kotlin 2.2.21 / Ktor 3.3.3) so the published SDK + plugin are consumable there. But only `protocol`,
  `sdk-android`, and `wailo-gradle-plugin` ever reach the consumer app. `engine`, `shared`, and `desktopApp` are
  desktop-side tooling that ships to no one — pinning them bought nothing and forced needless downgrades
  (Ktor, Kotlin, Gradle). A single Gradle build has exactly one Kotlin version and one Gradle version, so
  the desktop cannot diverge *within* the build; decoupling requires a second build.
- Decision: split into two independent Gradle builds in one repo, joined only by `protocol` consumed as a
  published binary:
  - **Root = SDK build** (stays on the ADR-0014 pin): `protocol`, `sdk-android`, `sample-android`,
    `sample-kmp`, plus the `wailo-gradle-plugin` included build. Publishes `wailo-protocol`, `wailo-android`,
    and the `com.venbiasa.wailo` plugin. Kotlin 2.2.21 / AGP 8.10.1 / Gradle 8.11.1 / Ktor 3.3.3 — and Ktor
    stays 3.3.3 because `sample-kmp:shared`'s iOS/native target still forces it (ADR-0014), so that pin is
    genuinely SDK-side, not desktop-side.
  - **`studio/` = desktop build** (modern; own wrapper + version catalog): `engine`, `shared`, `desktopApp`
    on Kotlin 2.3.21 / Gradle 9.3.1 / Ktor 3.4.3 / Compose Multiplatform 1.11.0. It consumes
    `com.venbiasa.wailo:wailo-protocol` from Maven Local — a Kotlin-2.2-built binary read by a 2.3 compiler
    is forward-compatible on the JVM, so there is no ABI problem (unlike the Kotlin/Native klib case that
    drove ADR-0014).
  - **`shared` drops its Android target** → JVM-only. It had no consumer (the viewer runs on the JVM), and
    dropping it keeps AGP out of `studio` entirely: the desktop build is pure Kotlin/JVM + Compose, needing
    no Android SDK. The `PointerCursor` expect now resolves against `jvmMain` alone; `androidMain` is deleted.
- Consequences:
  - The desktop stack is free to track modern Kotlin/Gradle/Ktor independently of whatever the consumer app pins the
    SDK to. ADR-0014 now scopes to the SDK build only.
  - New local workflow: `protocol` must be published (`./gradlew :protocol:publishToMavenLocal`) before the
    `studio` build can resolve it; editing `protocol` means republish, then rebuild `studio` (SNAPSHOT, so
    no version bump). The desktop build is invoked from its own dir: `cd studio && ./gradlew build`.
  - `engine` and `shared` reference `protocol` by Maven coordinate (`libs.wailo.protocol`) instead of the
    `projects.protocol` type-safe accessor — the one dependency that crosses the build boundary.
  - Two version catalogs now exist and intentionally differ: `gradle/libs.versions.toml` (SDK) and
    `studio/gradle/libs.versions.toml` (desktop). The "single catalog" convention is now per-build.
  - `studio` needs no Android SDK to build (no AGP), lightening setup for desktop-only contributors.
  - Kept as one repo (not two) for atomic commits + shared docs/ADRs. Revisit if the desktop ever needs its
    own release cadence.

## ADR-0016: Dark mode is first-class — every new design must work in light and dark

- Status: Accepted and built (top-bar toggle compiles in the `studio` build; `:desktopApp:classes` green).
  Builds on ADR-0013's light/dark theme and amends AGENTS.md "Conventions".
- Context: ADR-0013 shipped a full light+dark Material 3 theme generated from `tokens.json`, but the app
  only *followed the OS* (`isSystemInDarkTheme()` default) with no in-app override, and light/dark parity
  was left as an implicit, easily-skipped manual check. Dark mode should be a deliberate, tested product
  surface — not an accident of the OS setting — and designing for it must be a habit, not a retrofit.
- Decision:
  - Dark mode is a first-class, user-controllable mode. A top-bar toggle flips light/dark; the choice is
    host-owned (`desktopApp` `Main.kt`, mirroring the Cmd +/- text-scale pattern) and persisted through the
    `KeyValueStore` seam (`ThemeStore`), seeded from the OS on first run so behavior only improves on the
    old auto-only default. `shared` stays stateless over its inputs (ADR-0013): `WailoApp`/`WailoViewer`
    take `darkTheme` + `onToggleDarkTheme`, the host owns the state.
  - Going-forward rule: **every new screen/component is designed and verified in both appearances.** Colors
    come from `MaterialTheme.colorScheme` or the `WailoColors` CompositionLocal — never hard-coded; new
    colors are added as `tokens.json` entries for *both* schemes (the generated `theme/*.kt` stay
    DO-NOT-EDIT, ADR-0013). Icons are tint-driven (mono vector drawables recolored by `Icon`) so they
    invert with the theme instead of baking in a light-mode color.
  - Light/dark parity is part of the repo's manual two-tier verification (ADR-0013): smoke-check both via
    the toggle before UI work is called done.
- Consequences:
  - `ThemeStore` is one `getBoolean`/`putBoolean` binding, so it stays a plain `object` (not Koin) per the
    DI convention. Two rounded Material Symbols drawables were added (`ic_dark_mode`/`ic_light_mode`, mono).
  - A design that hard-codes a color or ships a non-tintable icon is now a **defect, not a style nit** — it
    will break one of the two schemes by construction.
  - Does not force a tri-state (System/Light/Dark); the binary toggle latches an explicit choice after first
    run. Revisit if a "follow system" option is wanted later.

## ADR-0017: iOS zero-install auto-start — `+load` hook in a dynamic SDK (implements ADR-0009's iOS half)

- Status: Accepted and built (`sdk-ios` compiles as `libWailoSDK.dylib`, `swift test` green, the target consumer app's SPM
  graph re-resolves). Implements the iOS `+load` auto-start that ADR-0009 fixed as the *approach* but left
  in the "Later" phase; amends the README's iOS usage.
- Context: `sdk-android` is already zero-code — `WailoStartupProvider` (a `ContentProvider` declared in the
  SDK manifest, merged into the host) runs before `Application.onCreate` and installs a default sink in
  debuggable builds (ADR-0009). iOS required an explicit `Wailo.start()` in the host's `AppDelegate`/`App`.
  iOS has **no manifest-merge equivalent**, so the only pre-`main` hook is an Objective-C `+load` (or a C
  constructor). Its reliability hinges on the object code actually being loaded: a `+load` in a *static*
  library is dead-stripped unless something references it or the host adds `-ObjC`/`-force_load` (the
  classic Firebase footgun). Swift has no `+load`, and a SwiftPM target is single-language.
- Decision:
  - Add a pre-`main` auto-start: a new single-purpose Objective-C target `WailoAutoStart` whose `+load`
    calls `Wailo.start()` with zero-config defaults (localhost:8899, bundle id, device name) — the timing
    and behavior match Android's `WailoStartupProvider`.
  - Ship the `WailoSDK` product as **`type: .dynamic`**. A dynamic image is always loaded at launch, so the
    `+load` reliably fires with **no host build flags** — the reason dynamic beats static+`-ObjC` for a
    "just link it" experience. The target consumer app's `use_frameworks!` world is already dynamic-friendly.
  - The `+load` target reaches Swift by **ObjC runtime name** (`NSClassFromString("WailoBootstrap")` →
    `autoStart`), via a thin `@objc(WailoBootstrap)` `NSObject` bridge (`Wailo` is a Swift `enum`, so it
    can't be `@objc`). This keeps the ObjC target free of any generated-header dependency on WailoSDK; the
    shared dynamic product guarantees the class is registered by the time `+load` runs.
  - Make `Wailo.start()` **idempotent** (stop the prior client before installing the new one) so the
    auto-installed default and a later explicit `Wailo.start(host:)` collapse to a single live session
    instead of leaking a second WebSocket — mirroring Android's `WailoRuntime.install`, which "cleanly
    replaces the auto-installed sink."
- Alternatives considered:
  - **Static product + require the host to add `-ObjC`** (Firebase-style): reliable, but not zero-config —
    it pushes a linker flag onto every consumer. Rejected as the default; dynamic needs nothing.
  - **C `__attribute__((constructor))` + `@_cdecl`**: works, but `@_cdecl` is an unofficial underscored
    attribute; ObjC `+load` is the documented mechanism ADR-0009 named and needs no unstable API.
  - **Info.plist-driven host/port** (à la `GoogleService-Info.plist`): deferred — the defaults suffice and
    overrides remain the explicit `Wailo.start(host:)`.
- Consequences:
  - **Zero startup code on iOS**: linking `WailoSDK` is enough; `Wailo.start()` is now only for
    customizing host/port (or extra sinks) and cleanly replaces the auto default. This reaches parity with
    Android's build-time-plugin + startup-provider model, by a different mechanism.
  - `WailoSDK` is now a **dynamic framework**. Xcode auto-embeds SPM dynamic products into app bundles and
    the CLI sample links via rpath; embedding is the one thing to smoke-check on first run per consumer.
  - **Not yet debug-gated.** Android gates auto-start on `FLAG_DEBUGGABLE`; this hook currently fires in any
    configuration, so it would dial `localhost:8899` in a *release* build too. Gating (e.g. `#if DEBUG`,
    which reflects the host config for source-built SwiftPM, or a `get-task-allow` runtime check) is a
    required follow-up before any release consumer ships it.
  - The samples' explicit `Wailo.start()` calls are now redundant (still valid — idempotent replace); they
    can be dropped to demonstrate zero-install, left as-is otherwise.
  - Verified by machine: `swift build` (links the dylib incl. `WailoAutoStart.m`), `swift test` (4 pass),
    `xcodebuild -resolvePackageDependencies` on the target consumer app. Live pre-`main` capture with no host code is a manual
    smoke check under the repo's two-tier verification.

## ADR-0018: Device-side WebSocket reconnection is self-healing (delegate + generation + ping keepalive); OS reachability deferred

- Status: Accepted and built. `swift test` green (adds `testReconnectsWhenServerStartsLate` and
  `testReconnectsAfterServerDrops`); `:sdk-android:testDebugUnitTest` green (adds `retriesUntilServerIsUp`).
- Context: ADR-0008/0010 specified "a background loop drains and auto-reconnects", but the two device ports
  diverged in how robustly they actually recover. Android's Ktor loop re-runs on *every* connection outcome
  (refused, dropped, closed), so it keeps retrying. The Swift port scheduled the next attempt *only* inside
  `handleDisconnect()`, which ran *only* when `URLSessionWebSocketTask` delivered a `send`/`receive` failure
  callback. `URLSessionWebSocketTask` does not guarantee those handlers fire on every failure — notably a
  connection that never establishes (desktop not up) or a silently dropped idle link — so a single missed
  callback left iOS "waiting" with no retry ever scheduled: it would not reconnect when the network came
  back. Separately, neither port detected a silently half-open *idle* socket (no traffic to fail on), and
  neither reacts to OS connectivity changes, so recovery was at best a blind poll.
- Decision:
  - iOS (`sdk-ios` `WailoClient`): make the transport `URLSession` carry a delegate and route
    `urlSession(_:task:didCompleteWithError:)` / `didCloseWith` into the same disconnect path. That delegate
    signal fires even when the `send`/`receive` handlers don't, so a failed or never-established attempt
    still schedules a retry — the fix that makes reconnection impossible to wedge. Guard every callback with
    a monotonic `generation` epoch (bumped on connect *and* disconnect) so a late/duplicate signal is a
    no-op and each disconnect retries exactly once. Add a `sendPing` keepalive while connected to provoke a
    failure (and reconnect) on a dead idle link. `stop()` calls `invalidateAndCancel()` on the session to
    break the delegate retain cycle — otherwise a replaced client (e.g. a second `Wailo.start`) leaks a live
    WebSocket.
  - Android (`sdk-android` `WailoClient`): set the Ktor client `pingIntervalMillis` so a dead idle link is
    actively probed, and consume `incoming` as the "await close" signal so a ping/pong timeout, drop, or
    server close ends the session block and the existing loop reconnects — draining `outbox` alone never
    observes an idle drop. Requeue the in-flight exchange when a send fails, so a mid-send drop doesn't lose
    it across the reconnect.
  - Keep the existing fixed ~2s reconnect backoff on both; the ping interval is ~20s.
- Alternatives considered:
  - **OS reachability monitoring** (`NWPathMonitor` on iOS, `ConnectivityManager` on Android) for an
    *instant* reconnect the moment connectivity returns. Deferred: the self-healing retry already recovers
    without it, and the Android side would add an `ACCESS_NETWORK_STATE` permission to a library that ships
    inside third-party apps (invariant #3) — an imposition that warrants its own decision. `NWPathMonitor`
    needs no permission and can be added on iOS alone later.
  - **Exponential backoff**: unnecessary at a 2s interval to a single localhost/LAN peer; revisit only if it
    ever hammers a busy network.
- Consequences:
  - Both device SDKs now reliably reconnect after the desktop starts late, quits, or the link drops or goes
    idle — no app restart — and this is proven by machine (loopback reconnect tests), not just by
    inspection.
  - The two ports stay aligned on the wire contract but differ in mechanism (URLSession delegate + ping vs.
    Ktor pinger + `incoming`), the accepted device-side duplication of ADR-0010/0011.
  - Recovery latency is still bounded by the 2s poll (plus up to the ping interval to notice a *silent* idle
    drop), not instant; closing that gap is the deferred reachability follow-up above.

## ADR-0019: Map Local — device caches match-metadata only, fetches bodies lazily; rules are versioned + acked (anti-entropy)

- Status: Accepted; building. Supersedes the initial Map Local behavior (a `RuleSet` snapshot with response
  bodies inlined and cached whole on the device — that feature shipped without its own ADR; this records the
  model going forward).
- Context: The first Map Local cut pushed a `RuleSet` where each rule carried its response *bytes* inline, and
  the device cached the whole set (connection-scoped after the disconnect-clear change). Two problems drove a
  redesign: (1) **memory** — inlining bodies means the device holds every mapped file resident in RAM for the
  whole session, which is bad for large fixtures or many rules and fights invariant #3 (the SDK ships inside
  third-party apps; keep it light); (2) **silent desync** — pushes are fire-and-forget with no ack, so a push
  lost on a still-alive link leaves the device stale and the desktop unaware, with no repair until the next
  edit or reconnect. Query-the-desktop-per-request was considered and rejected: it taxes *all* traffic (even
  the ~99% that never match) with a round-trip, couples every request's latency to the desktop, and risks
  OkHttp dispatcher-thread exhaustion on Android. Map Local is automated (no human in the loop), so the match
  decision should stay local and instant; only the (occasional) matched body needs the authority.
- Decision:
  - **Device caches match-metadata only** (`MapLocalRule` = id, enabled, url_pattern, methods). Response bodies
    are never stored on the device. Cache stays connection-scoped (cleared on disconnect, ADR follows the
    existing device behavior).
  - **Lazy body fetch on match**: the device matches locally, then fetches the response (code, headers, body)
    from the desktop via a request-scoped RPC — `BodyRequest`/`BodyResponse` keyed by a `correlation_id`,
    with a device-side pending map. The request suspends until the reply arrives (event-driven on iOS
    `URLProtocol`; async/timeout on Android to avoid blocking OkHttp threads). Bodies live in device memory
    only for the life of that one request, and are always fresh (authority-sourced).
  - **Fail-open**: if the fetch can't complete (not connected, timeout, `found=false`, or a disconnect
    mid-fetch), the device makes its own real network call. We are not a proxy — the SDK always owns the real
    request — so a desktop hiccup degrades to live traffic, never a hung/failed request.
  - **Anti-entropy sync for the metadata**: `RuleSet` carries a monotonic `epoch` owned by the desktop. The
    device applies each snapshot as a full replace (unconditionally) and returns `RuleAck{epoch}`. The desktop
    tracks per-session `ackedEpoch` and re-pushes on a timer while a session is behind, so a lost push
    self-repairs; a reconnect re-syncs from scratch. `epoch` never gates application (only ack-matching, retry,
    and future sync-status display), so a desktop restart resetting the counter is harmless.
  - **Engine stays headless**: body resolution is a UI-agnostic `MapLocalBodyProvider` seam the desktop
    supplies (it reads the file for a given rule id). The engine never learns file paths and never reads the
    filesystem itself.
- Alternatives considered:
  - **Query-per-request availability** (no cache): rejected for the whole-traffic latency/thread cost above.
  - **Inline bodies in the snapshot** (the initial model): rejected for memory + bandwidth (holds all fixtures
    resident; re-pushes all bytes on every edit).
  - **No ack, reconnect-only repair**: leaves the silent-push-loss window open until the next edit/disconnect.
- Consequences:
  - The socket becomes bidirectional request/reply: versioned rule pushes + `RuleAck` + a `BodyRequest`/
    `BodyResponse` correlation channel. This is the same correlation/pending-map machinery breakpoints will
    need — built once here.
  - Device Map Local memory is now ~O(rule count × small metadata), independent of fixture size; a matched
    request pays one device↔desktop round-trip (only on matches).
  - **Android does not yet apply rules** (it ignores inbound frames); when it does, it must mirror this model
    — metadata cache, lazy fetch, fail-open, ack — since consistency across the two SDKs is kept only by the
    wire contract (invariant #4), never by shared device code.
  - Desync is now both self-repairing (ack + retry + reconnect) and observable (the desktop knows each
    device's `ackedEpoch` vs the current `epoch`), enabling a future "rules out of sync" indicator.

## ADR-0020: Map Local response-body editor — inline app-managed bodies + a Swing code editor behind a portable seam

- Status: Accepted; building. Extends Map Local (ADR-0019) with an in-desktop body editor; adds one
  desktop-only dependency (RSyntaxTextArea) and a new `shared` expect/actual seam.
- Context: Map Local rules could only *reference a file on disk* — there was no way to author or tweak
  a response body inside the desktop. The ask is an editor for JSON responses that stays smooth on large
  payloads (target ~10 MB). Two forces shaped the design: (1) ADR-0019 makes the desktop serve a matched
  body by reading a *file fresh from disk at request time* (device holds no bodies, engine stays headless);
  (2) Compose's own text field (`BasicTextField`) re-lays-out the entire string on every edit — O(n) per
  keystroke — so it stalls badly past a few hundred KB, i.e. it cannot meet the performance requirement.
  A separate concern was raised: how does this scale to a future *mobile* preview/editor?
- Key distinction (preview vs editor): the read-only JSON **preview** (`BodyPreview`) is already a
  virtualized `LazyColumn` of lines in `commonMain`, so it composes only the visible rows, stays cheap on
  huge JSON, and *already ports to Android/iOS unchanged*. Only huge-text **editing** needs a non-Compose
  engine (Compose has no viewport-virtualized editable text). So mobile-preview is not blocked by the
  desktop editor's technology.
- Decision:
  - **Dual body source, still file-backed** (ADR-0019 unchanged). `MapLocalRuleDef` gains an `inline` flag:
    a rule serves either from the user's file (`filePath`, read fresh) or from an inline body the desktop
    editor authors. An inline body is persisted as an app-managed file (`<app-data>/Wailo/maplocal-bodies/
    <ruleId>.json`), and `serveBody` reads *that path* fresh per request — so the device still caches only
    match-metadata, the desktop still reads bytes at request time, and the engine never learns paths. The
    protocol, engine, and device SDKs are untouched.
  - **Editor = RSyntaxTextArea via Compose Desktop `SwingPanel`**, behind a portable `CodeEditor`
    expect/actual seam in `shared` whose signature carries no Swing types (text flows through a
    `CodeEditorState` holder; the big document lives in the widget, never in Compose snapshot state, so a
    keystroke never recomposes/re-copies megabytes). RSTA tokenizes and renders by viewport, so it stays
    smooth into the multi-MB range; code folding (a whole-document parse) is disabled above ~1 MB. It is
    themed from the design tokens (`colorScheme` + `WailoColors`, re-applied in `update`) so it honors
    light/dark (ADR-0016), and its font rides the density `fontScale` so Cmd +/- scales it.
  - **Format + validate reuse the existing hand-rolled JSON** (`prettyPrintJson`/`parseJson` +
    `jsonErrorMessage`), keeping the module's dependency-light JSON stance; validity is a debounced live
    status line (read off `snapshotFlow`, never per keystroke).
  - **Seed-from-capture**: the traffic-row "Map Local…" action now carries the captured response body
    (decoded/pretty-printed when textual, size-capped) to prefill a new inline rule's editor.
  - **New dependency**: `com.fifesoft:rsyntaxtextarea` (BSD-3), in the `studio` catalog and only in
    `shared`'s `jvmMain` — recorded here per the "a new dependency needs an ADR + catalog entry" convention.
- Portability / how mobile scales later: the `CodeEditor` seam is the firewall. The read-only preview
  stays Compose-native (already mobile-ready); the *editor* is per-platform behind the seam — desktop is
  RSTA now, and a future mobile `actual` (a native editor, or a WebView + CodeMirror for true write-once)
  can back the identical contract with **zero call-site churn**. This mirrors the device-SDK philosophy
  (ADR-0010/0011): single-source the contract, implement per platform.
- Alternatives considered:
  - **Compose-native `BasicTextField` (with a size cap)**: not a real huge-text editor (O(n)/keystroke);
    rejected because it defeats the stated performance goal.
  - **A Compose-native line-virtualized editor now** (focused-line editable + `LazyColumn`): rendering is
    easy, but a genuine editable one is effectively a text-editor engine — cross-line selection over
    recycled items, a single logical caret, IME correctness (the hard part, and exactly what mobile needs),
    and the minified single-giant-line case. Deferred behind the seam rather than gambling the ship-now
    feature on it.
  - **WebView + CodeMirror/Monaco, write-once across desktop + mobile** (editor + preview + scripting):
    the most future-proof, but a heavy desktop runtime (JCEF/KCEF ~150 MB) + JS-bridge/asset work; it is
    its own milestone/ADR to adopt if cross-platform *editing* becomes a committed requirement. The seam
    keeps this open.
  - **Store inline bodies in the rule/prefs**: rejected — prefs is for small values, it would hold bodies
    resident and re-serialize them on every edit, and it fights ADR-0019's read-fresh model. App-managed
    files keep `serveBody` a path read.
- Consequences:
  - Desktop-only Swing interop now lives in `shared/jvmMain` behind the expect/actual, with its known
    caveats (a heavyweight component's z-order over Compose popups; theming/scale wired by hand). Acceptable
    for a full-pane editor in its own window.
  - A new app-managed body directory with a lifecycle: written on inline save, deleted on rule delete or a
    switch back to a file source. The `MapLocalStore` prefs line grows 7→8 fields; legacy 7-field lines
    still load (as file-backed).
  - Verified by machine: `:shared:jvmTest` (adds `jsonErrorMessage` cases) + the `studio` build. Live
    editing, large-JSON smoothness, seed-from-capture, and light/dark parity are manual smoke checks under
    the repo's two-tier verification.

## ADR-0021: Map Local is a docked right-side tool panel, single-method, seeded exactly from a row

- Status: Accepted; building. Refines Map Local's desktop UX (ADR-0019/0020): it supersedes the
  "separate resizable window" placement those ADRs assumed, and narrows the method match to one HTTP
  method. Rewrites ADR-0020's "editor in its own window" consequence.
- Context: Map Local opened in its **own OS window** (a locked UI decision at the time), launched from a
  left nav-rail item. Three rough edges drove this revision: (1) a separate window is heavier than the
  feature warrants and detaches the rules from the traffic you're mapping — the reference tools dock it beside the traffic; (2) the rule matched a *comma-separated list* of methods via a
  free-text field, which is fiddly and overkill — a mapped endpoint is almost always one method; (3) the
  per-row "Map Local…" only pre-filled a *wildcard* URL pattern and no method, so the user still had to
  retype the very values they right-clicked on.
- Decision:
  - **Placement = an in-window right tool panel**, the Android Studio tool-window model: a thin right
    **tool rail** (icon-only) toggles a **docked, resizable** panel between the content and the rail. Map
    Local moves off the left nav rail onto the right rail — and with Map Local gone, Traffic was the rail's
    only view, so the **left nav rail is dropped entirely** and the traffic list now spans to the window's
    left edge. No separate window. The panel's open state, the current row-seeded draft, and its width are
    the viewer's own **transient view state**; the host still owns the rules + persistence (`shared` stays
    stateless, ADR-0013).
  - **One method, via a dropdown.** `MapLocalRuleDef.methods: List<String>` becomes `method: String`
    (blank = any); the editor picks a single HTTP method from a dropdown (Any/GET/POST/PUT/PATCH/DELETE/
    HEAD/OPTIONS). The wire type keeps its `methods` list — `compileRules` maps a blank to `[]` (any) and
    a concrete method to a singleton — so the protocol/engine/device matching are untouched. The
    `MapLocalStore` line keeps its 8 fields; a legacy multi-method field collapses to its first entry.
  - **Seed a row exactly.** The traffic-row "Map Local…" now pre-fills the **exact** captured URL (not a
    wildcarded pattern — `patternFor` is dropped) and the **exact** method, alongside the existing JSON
    body seed. Map Local stays JSON-focused: the body seed is the decoded/pretty-printed response when
    textual, a row seed also carries the captured response's Content-Type (falling back to
    `application/json`), and new rules default to a `Content-Type: application/json` header + the inline
    editor.
  - **Body & Headers are tabs; Content-Type is just a header.** The editor splits the response into a
    **Body | Headers** tab pair under the compact rule fields (URL/method/status/enabled), so each gets
    the narrow panel's full height. A single raw status+headers+body buffer was rejected: it breaks the
    JSON editor's Format/validation, which assume the buffer is only the body keep Map
    Local structured too — raw editing is their *breakpoint* tool, a different feature). Response
    **headers** are now editable, and that is a **desktop-only** change: the served `BodyResponse` already
    carried `repeated Header` end-to-end (engine `ServedBody`, iOS `WailoMappedResponse` — it is how
    Content-Type was already applied), so no protocol/engine/SDK edit was needed. The old dedicated
    Content-Type field folds into the headers table (a new/seeded rule starts with a `Content-Type` row);
    the host still owns `Content-Length` — it recomputes it from the bytes and drops any hand-entered one —
    and falls back to the extension-guessed Content-Type when the rule sets none.
  - **Z-order via interop blending.** The method dropdown is a Compose popup that can overlap the inline
    editor's embedded Swing panel (the z-order caveat ADR-0020 flagged). The host sets
    `compose.interop.blending=true` before any Compose code so popups composite above the Swing panel
    (effective on macOS/Metal + Windows/Direct3D; a harmless no-op elsewhere).
- Alternatives considered:
  - **Keep the separate window**: rejected — heavier than the feature and detached from the traffic.
  - **Bottom panel (like the detail panel)** or a **left panel**: rejected — the bottom is the
    request/response detail's home, and Map Local is a right-hand *tool* in the reference-tool mental
    model; the right rail also leaves room for future tools.
  - **`compose.layers.type=WINDOW`** (popups as separate OS windows) instead of blending: also fixes the
    z-order and avoids the macOS event-order caveat, but changes *every* popup app-wide (context menus,
    tooltips) — a larger blast radius than compositing just the Swing panel. Revisit if blending's macOS
    event caveat bites the dropdown in practice.
  - **Method chips / free-text**: chips avoid the popup entirely but aren't the requested dropdown;
    free-text multi-method is the fiddly status quo being removed.
- Consequences:
  - `WailoApp`/`WailoViewer` gain the Map Local rules + persistence callbacks (was two window-launch
    lambdas); the viewer renders the panel inline and owns its open/draft/width state. `Main.kt` drops the
    second `Window` and its theme/scale wrapper (the panel is a child of the main composition, so it
    inherits both).
  - The inline body editor's Swing panel now lives in the **main** window, not a dedicated one — so
    ADR-0020's "acceptable in its own window" no longer holds; interop blending is what keeps popups
    usable over it. This is an experimental flag; light/dark, the dropdown-over-editor interaction, panel
    resize, and row-seed-exactness are manual smoke checks under the repo's two-tier verification.
  - The editor **fills** the payload area and is never wrapped in a Compose `verticalScroll`: a
    heavyweight `SwingPanel` visibly flickers as an enclosing scroll repositions it each frame. So the
    compact rule fields stay pinned above the tabs, and the editor (like the Headers tab) scrolls its own
    content instead — RSTA's native scrollbars for the body, a Compose scroll for the all-Compose Headers
    table. The editor grows with the panel/window rather than offering a drag-to-resize handle.
  - **Body source is now always inline**, reversing the ADR-0019/0020 dual (inline vs. user-chosen file):
    the "Local file" radio + native file picker are dropped, so every rule authors its body here and the
    host persists it to the app-managed store. `MapLocalRuleDef` keeps `inline`/`filePath` (and the rule
    row still labels a legacy file-backed rule) so old prefs load unchanged, but the editor always writes
    `inline = true`, and the `onPickMapLocalFile`/`chooseLocalFile` chain (down through `WailoApp`/
    `WailoViewer`) is removed. Rationale: the file source added a mode toggle and a second IO path for a
    payload the user almost always edits by hand anyway.
  - The live JSON **verdict moved into the footer**, sharing the line with Cancel/Save instead of a
    dedicated status row above the editor — reclaiming vertical space for the editor — and it now wraps
    (multiline) rather than truncating so a parse error stays fully readable.
  - Added one mono vector drawable (`ic_arrow_drop_down`) for the dropdown affordance (tint-driven,
    light/dark-safe per ADR-0016).
  - The left nav rail (`NavRail`) is removed: once Map Local moved to the right rail its only entry was
    the always-selected Traffic view, so a whole rail (plus its collapse toggle) earned no keep. The
    viewer drops the rail, its divider, and the `railCollapsed` state; `NavRail.kt` is deleted and the
    traffic list is now the left-most pane.
  - `MapLocalRuleDef.contentType: String` becomes `headers: List<MapLocalHeader>`. The `MapLocalStore`
    line reuses the old content-type slot for a headers blob (`enc(name):enc(value)` joined by `,`, every
    token Base64 so a value can't collide with the `|`/line delimiters) and migrates a legacy lone
    content-type to a `Content-Type` header; the field count is unchanged, so old prefs still load.
  - Hosting the inline editor behind a tab means its Swing panel unmounts when Headers shows, so
    `CodeEditorState` snapshots the live text into its seed on dispose (and reseeds on remount) — else a
    tab switch, or a Save from the Headers tab, would read the stale initial text.

## ADR-0022: Map Local panel navigates via Navigation 3; Add is a FAB; the nav layer is multiplatform-ready

- Status: Accepted; building. Extends ADR-0021's docked tool panel with an explicit page stack and
  reworks two of its affordances (Add, Back). Records how far the navigation is deliberately made
  portable ahead of the rest of the panel.
- Context: ADR-0021's panel has two pages — the rule **list** and the **Mapping Rule** editor — but
  switched between them with a single `editing: MapLocalRuleDef?` state var (non-null = editor, null =
  list). Two rough edges: (1) there was no explicit **Back** from the editor to the list — only a footer
  *Cancel* and the panel-wide *Close* — so "return to the rules" wasn't a first-class, discoverable
  action; and (2) **Add** was a labeled button crammed into the list header beside Close. Separately, the
  project may later grow beyond the JVM desktop viewer (mobile/web companions), so new UI structure
  should lean multiplatform where that is cheap.
- Decision:
  - **Navigation 3 owns the panel's page stack.** Adopt the JetBrains multiplatform build of Nav3
    (`org.jetbrains.androidx.navigation3:navigation3-ui`, **stable 1.1.1**): a `NavDisplay` over a
    user-owned back stack of two destinations (`RuleListDestination`, `RuleEditorDestination(ruleId)`).
    The back stack is the single source of truth for which page shows and for Back; `MapLocalManager`
    drops the `editing` flag. This is the repo's first navigation library, so it lands with a version
    catalog entry (following the Koin precedent for new frameworks), not a hard-coded version.
  - **Add is a FAB.** "Add rule" leaves the header (now just title + Close) for an **icon-only**
    `FloatingActionButton` pinned bottom-right; the list carries a bottom content inset so the FAB never
    hides the last row's controls. New rules still default to inline + a `Content-Type: application/json`
    header (ADR-0021).
  - **Editor gets a Back affordance; Cancel is dropped.** The Mapping Rule header leads with a back arrow
    (new `ic_arrow_back` mono drawable, tint-driven per ADR-0016) that pops to the list. That made the
    footer *Cancel* redundant (both merely returned to the list), so the footer is now the JSON verdict +
    **Save** only; *Close* still dismisses the whole panel. Save/Back/predictive-back all pop the stack,
    guarded so the root list is never popped (an empty `NavDisplay` back stack is illegal).
  - **The nav layer is deliberately multiplatform-ready.** Destination keys are `@Serializable` + `NavKey`
    and the back stack is built with an explicit `SavedStateConfiguration` polymorphic module — **not**
    the reflection-based `rememberNavBackStack` overload, which is JVM/Android-only. So the navigation
    code (in `commonMain`) would compile and restore state on Android/iOS/web unchanged. Cost: one
    first-party Kotlin plugin (`kotlin.plugin.serialization`, ref'd to the Kotlin version — a zero-risk
    toolchain addition) plus ~10 lines of config.
  - **The rest of the panel is *not* multiplatform yet, and closing that gap is a much larger effort.**
    Making Map Local actually *run* on mobile/web is out of scope here and is gated on, in rough order of
    cost: (a) the response-body editor is a **Swing** `SwingPanel` behind the `CodeEditor` expect/actual
    (ADR-0020) — desktop/JVM-only, with no iOS/web/Android actual; (b) `shared` is **JVM-only** and
    `engine` is JVM (ADR-0015 dropped `shared`'s Android target on purpose), so the module would need
    targets re-added plus a portable body/key-value store; (c) the transport/host wiring is JVM. The
    navigation seam is made portable now because it is ~10 lines; the editor and module targets are the
    real work, to be chosen per-capability once a concrete non-desktop client exists.
- Alternatives considered:
  - **Keep the `editing` flag + add only a back arrow** (no library): the smallest change and it fully
    meets "Back to the list". Kept as the documented fallback if Nav3 proves heavy; not the primary path
    only because the ask was to standardize on Nav3, which now has a stable multiplatform release.
  - **Hand-rolled `List<Screen>` back stack**: real stack semantics, zero dependencies — but reinvents
    what Nav3 provides (transitions, per-entry saveable-state scoping) and wouldn't match a future
    app-wide nav choice.
  - **Reflection-based `rememberNavBackStack(key)`**: fewer lines and fine on JVM+Android, but
    *compiles-then-crashes* on iOS/web (no reflection). Rejected — the explicit config is ~10 lines and
    truly portable, so the reflection overload would be a latent footgun the moment `shared` gains a
    native target.
  - **`androidx.navigation3` (Google, Android-only stable)** instead of the JetBrains multiplatform
    build: rejected — it ships no JVM-desktop artifact, so it can't run the desktop viewer at all.
- Consequences:
  - New studio-build dependencies (catalog): `navigation3 = "1.1.1"` (`navigation3-ui`) + the
    `kotlinSerialization` plugin. `navigation3-ui` is `implementation` in `shared`, so it rides onto
    `desktopApp`'s runtime classpath transitively; `MapLocalManager`'s public signature is unchanged, so
    `WailoViewer`/`WailoApp`/`Main.kt` are untouched.
  - The editor's inputs (the rule being authored + any captured body seed) live in transient
    `drafts`/`bodySeeds` maps keyed by rule id — out of the nav key, which stays a small id — because a
    new/seeded rule isn't in the host's `rules` yet and a body seed is too large to ride in a key. These
    are **not** restored across process death (an interrupted draft is cheap to re-open); Nav3's saveable
    back stack restores *which* page you were on, not an unsaved draft's contents.
  - A row-seeded launch resets the stack to `[list, editor]` (via `LaunchedEffect(initialDraft)`) so Back
    lands on the list even when the panel opened straight into the editor from a traffic row.
  - Added one mono vector drawable (`ic_arrow_back`).
  - Manual smoke checks (two-tier verification): the Add FAB opens the editor; the editor's Back arrow and
    a successful Save both return to the list; a row's "Map Local…" opens the editor with Back → list;
    light/dark both read correctly. Runtime-only Nav3 behavior (page transitions; that the default entry
    decorators resolve without the separate ViewModel artifact) isn't caught by the compile and is part
    of this smoke pass.

## ADR-0023: Replace the Swing code editor with a Compose-native, viewport-virtualized editor

- Status: Accepted; building. **Reverses ADR-0020's editor technology** (`RSyntaxTextArea` via `SwingPanel`)
  while keeping ADR-0020's *architecture* (the portable `CodeEditor` expect/actual seam, the `CodeEditorState`
  holder, inline app-managed bodies, and hand-rolled JSON format/validate). ADR-0021's interop-blending
  rationale is now vestigial for this editor (see Consequences).
- Context: ADR-0020 chose RSTA precisely because Compose's `BasicTextField` lays the *entire* document out as
  one `MultiParagraph` on every edit — O(n) per keystroke — so it stalls past a few hundred KB. RSTA solved
  the performance requirement but cost us: (1) it is the app's **only** heavyweight AWT/Swing component, so
  the **first** open of the Mapping Rule editor pays a large one-time cold-start (Compose↔Swing interop
  bootstrap — worsened by `compose.interop.blending=true` — plus RSTA class-loading and the AWT font
  manager), all synchronous on the UI/EDT thread, which janks the list→editor transition; and (2) it is
  desktop/JVM-only, so it is the single biggest blocker to ever running Map Local on mobile/web (ADR-0022's
  "not multiplatform yet" gap (a)).
- Key insight: Compose *can* do this — the O(n) cost is in the *layout of one giant `MultiParagraph`*, not in
  the buffer. If we **virtualize by line** (only lay out visible lines, like the read-only `BodyPreview`
  tree/hex already do with `LazyColumn`) and keep the text in a **line-model buffer** (not one immutable
  `String`), then per-keystroke cost is bounded to *one line + the viewport*, independent of document size.
  Pretty-printing on load is load-bearing here: it converts a minified one-liner into many short lines, which
  is exactly what line-virtualization needs (a single 10 MB line would defeat it).
- Decision:
  - **A Compose-native `CodeEditor`, in `commonMain`.** A `LazyColumn` of highlighted lines over an
    `EditorBuffer` (an `ArrayList<String>` of lines; edits splice the affected line span, O(1) amortized for
    typing). `CodeEditorState` is rebuilt around that buffer + caret/selection/undo as Compose state, but
    **keeps its existing contract** (`currentText`/`setText`/`touch`/`revision`) so `RuleEditor` and the
    debounced-validation effect are unchanged. The big document lives in the buffer, never in a single
    snapshot `String`, so a keystroke never re-copies megabytes (same principle ADR-0020 stated, now met
    without Swing).
  - **Custom caret/selection/input model as the single source of truth.** Full cross-line selection +
    copy/cut/paste, caret navigation (arrows/home/end/page, shift to extend), undo/redo (coalesced typing),
    auto-indent on Enter, auto-close brackets/quotes, a line-number gutter, current-line highlight,
    horizontal scroll for long lines, and Cmd/Ctrl+F find. Monospace makes hit-testing/caret math a constant
    `charWidth`. JSON highlighting is a **per-line** tokenizer (`jsonHighlightSpans`) — safe because a JSON
    string literal can't span lines — so highlighting cost is viewport-bounded and size-independent; it is
    reused verbatim for the active line and the static lines (one visual language).
  - **Text input is via key events (`utf16CodePoint`), desktop-first.** Correct for authoring/pasting JSON on
    desktop; on-screen-keyboard **IME is a documented follow-up** behind the same seam (the component's API is
    already portable; only the input source is desktop-oriented). This is the one honest gap versus a fully
    mobile-ready editor, called out so it isn't mistaken for done.
  - **Size thresholds (tunable constants).** Auto-format (pretty-print) on load/seed/save up to ~10 MB
    (skip + notice beyond — the format pass is a cheap O(n) pass run off the UI thread, and it is what keeps
    big payloads renderable); live JSON validation up to ~2 MB (debounced/off-thread, else validate on Save);
    per-line tokenizing skipped for a pathological single line (> ~10k chars); smooth-editing target ~5 MB.
  - **RSTA kept temporarily behind a flag.** `libs.rsyntaxtextarea` stays in the `studio` catalog and the
    Swing editor stays compiled (self-contained, `SwingCodeEditor`) but **unwired**; the JVM `actual`
    dispatches to the Compose editor by default via a `const val`, so a regression is one-line-revertible.
    A follow-up deletes the Swing editor + the dependency once the Compose editor is proven, at which point
    the `CodeEditor` expect/actual can collapse to a plain `commonMain` composable.
- Alternatives considered:
  - **Keep RSTA, just prewarm it off the critical path** (background class-load + a throwaway `SwingPanel` at
    startup): fixes the first-open jank cheaply but keeps the desktop-only blocker and the AWT component in
    the main window. A good fallback; rejected as the primary path because it does nothing for portability.
  - **One `BasicTextField`/`TextFieldState` over the whole doc**: still lays out the whole `MultiParagraph`
    per edit → the exact O(n) stall ADR-0020 fled. Rejected.
  - **A windowed single `BasicTextField`** (edit only the visible slice, swap on scroll): keeps native
    within-window selection/IME but the slice-swap and absolute-offset mapping are janky and complex, and
    selection can't exceed the window. Rejected for the line-model, which is simpler to reason about.
  - **Per-line `BasicTextField` for every line**: gets IME per line but makes cross-line selection (the
    explicit requirement) very hard (Compose selection doesn't span sibling fields). Rejected.
- Consequences:
  - The editor moves to `commonMain`; `EditorBuffer` and `jsonHighlightSpans` are pure and unit-tested
    (the "testable so it won't break later" tier). `BodyTab`'s call site is unchanged (still `CodeEditor`).
  - ADR-0021's `compose.interop.blending=true` was set so the method dropdown could composite over the Swing
    panel. With no Swing panel in the editor, that flag is **no longer required for correctness** here; it is
    left in place for now (harmless) and its removal is folded into the RSTA-deletion follow-up.
  - New manual smoke checks (non-machine-verifiable): type/select/copy/paste/undo/find in the body editor;
    scroll a large (multi-MB) pretty-printed body and confirm smoothness incl. first open; JSON highlighting
    and current-line/selection read correctly in **both** light and dark; Cmd +/- still scales the editor.
  - Risk acknowledged: a hand-rolled editor is less battle-tested than RSTA (edge cases: bidi/RTL, complex
    IME, extremely long single lines). The flag-gated RSTA fallback and the ~5 MB target bound that risk.

## ADR-0024: Sunset the Swing / RSyntaxTextArea code editor

- Status: Accepted. Completes the follow-up ADR-0023 deferred: with the Compose-native editor proven as the
  default, the flag-gated Swing/RSTA fallback and its dependency are removed.
- Context: ADR-0023 shipped the Compose-native `CodeEditor` as the default (`USE_SWING_FALLBACK = false`) but
  kept `SwingCodeEditor` compiled behind a JVM `actual`, plus `libs.rsyntaxtextarea` in the catalog, so a
  regression was one line to revert. That safety net has outlived its purpose and was the last thing keeping a
  heavyweight AWT/Swing component — and a desktop-only dependency — in the studio build.
- Decision:
  - Delete `SwingCodeEditor` and the `CodeEditor` JVM `actual`; collapse the `expect/actual` seam to a plain
    `commonMain` composable (`CodeEditor`, renamed from `ComposeCodeEditor`). `CodeEditorState` drops
    `replaceAllFromWidget`, which only the widget-backed fallback used. The two `shared/commonMain` files are
    renamed to match: the model is `CodeEditorState.kt`, the composable `CodeEditor.kt`.
  - Remove `com.fifesoft:rsyntaxtextarea` from the `studio` catalog and `shared`'s `jvmMain` (it was the only
    `jvmMain` dependency, so the block goes with it).
  - Remove `compose.interop.blending=true` from the desktop `main()`. ADR-0021 set it so Compose popups (the
    method dropdown, context menus) could composite over the editor's `SwingPanel`; with no `SwingPanel` left
    anywhere in the app it is dead code. ADR-0023 already scoped this removal to this follow-up.
- Consequences:
  - `shared` is free of Swing/AWT interop; the editor is a single Compose composable in `commonMain`, one step
    closer to running Map Local on non-JVM targets (ADR-0022's gap (a)).
  - The one-line RSTA revert is gone: a future regression in the hand-rolled editor is fixed forward, not by
    flipping back to Swing (the risk ADR-0023 bounded is now accepted outright).
  - Manual smoke is unchanged from ADR-0023 (type/select/copy/paste/undo/find; large-body scroll incl. first
    open; JSON highlighting and current-line/selection in both light and dark; Cmd +/- scaling) — plus, since
    interop blending is off, confirm the method dropdown and context menus still render above the editor.
