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

## ADR-0025: The device SDK is a live tap — drop traffic captured while disconnected instead of buffering a replay backlog (amends ADR-0018)

- Status: Accepted; building. `:sdk-android:testDebugUnitTest` green (renames `retriesUntilServerIsUp` →
  `dropsWhileDownButStreamsAfterReconnect` and asserts an exchange captured while down is dropped, not replayed);
  `sdk-ios` `swift test` green (same rename, plus the post-drop send now waits for the reconnect Hello).
- Context: the desktop `engine` keeps captured exchanges in memory only — it persists nothing across its own
  restart (ADR-0003/0008). Yet both device SDKs buffered captured exchanges in a bounded drop-oldest queue
  while the desktop was down and flushed the whole backlog on reconnect (ADR-0008/0010/0011), and ADR-0018
  additionally requeued an in-flight exchange on a mid-send failure. Observed consequence: after the desktop
  crashed and was relaunched minutes later, the still-running device replayed up to ~512 stale exchanges, so
  "old traffic" reappeared in a freshly started inspector even though the desktop itself kept nothing. For an
  interactive inspector the intuitive contract is a live tap — you see what is happening while you are
  connected, not a backlog from while you were away.
- Decision:
  - Make both device SDKs a *live tap*: an exchange is delivered only while a connection is live and dropped
    otherwise; nothing is retained across a disconnect, so a reconnect never replays past traffic.
    - Android (`sdk-android` `WailoClient`): replace the long-lived `outbox` with a per-connection `Channel`
      held in a `@Volatile live`. `onExchange` does `live?.trySend(...)` (drops when null). The channel is
      created once the WebSocket is open, set as `live`, drained after Hello, and on disconnect `live` is
      cleared and the channel discarded. The ADR-0018 mid-send requeue is removed (a failed send drops it).
    - iOS (`sdk-ios` `WailoClient`): `onExchange` enqueues only while `task != nil`, and `handleDisconnect`
      clears the buffer so nothing survives a drop.
  - Keep every recovery mechanism ADR-0018 added: the always-on reconnect loop, the ~2s backoff, and the ping
    keepalive that detects a silently dropped idle link. A stale/lost connection still self-heals for *future*
    traffic — only the replay of *past* traffic is removed. The bounded drop-oldest buffer (cap 512) is
    retained solely to keep `onExchange` off the caller's thread and to smooth a burst on a live link; being
    discarded on disconnect, it is not a cross-outage store.
- Alternatives considered:
  - **Per-exchange TTL** so only "recent" backlog replays. Rejected: still a surprising partial replay, and it
    couples the wire to wall-clock/skew for little benefit on an interactive tool.
  - **Persist capture on the desktop** so a restart restores the list. Rejected as orthogonal and explicitly
    unwanted here — the ask was to *stop* stale traffic reappearing, not to make it durable.
- Consequences:
  - After a desktop crash/restart a freshly started inspector shows only traffic captured after it reconnects —
    no stale backlog. The two SDKs stay behaviorally aligned (live tap) and consistent with the desktop's own
    no-persistence model.
  - Traffic generated while the desktop is down, or during the reconnect gap, is now genuinely lost by design;
    a request that races a mid-send drop is also lost (no requeue). Acceptable for an interactive inspector — a
    future headless/record mode that must not lose traffic would need its own durable sink (its own ADR), not
    this live path.
  - Amends the "drain the buffer / buffered exchanges wait" wording of ADR-0008/0010/0011 and the ADR-0018
    requeue; ADR-0018's reconnection + keepalive mechanism is otherwise unchanged.
  - Manual smoke: with a device streaming, kill the desktop, keep the app active a few minutes, relaunch — the
    list starts empty and fills only with new requests.

## ADR-0026: Map Local rules gain single-level groups; list order is match priority; drag-and-drop is hand-rolled

- Status: Accepted; building. `:shared:jvmTest` green (new `MapLocalLayoutTest` covers flatten/priority,
  effective-enabled, the mutation + move ops, and codec round-trip + legacy migration); studio compiles
  (`:desktopApp:classes`, `:shared:compileKotlinJvm`).
- Context: Map Local shipped as a flat, unordered list of rules (ADR-0019/0020/0021), each independently
  on/off. Two gaps surfaced with real use: (a) no way to organize related rules or toggle them as a set (e.g.
  a whole "staging mocks" bundle), and (b) when two rules could match the same request the winner was
  undefined — the device returned "some" match, not a *chosen* one. Users expect a debugger's rule list to
  read top-to-bottom as priority (the reference proxy tools work this way) and to fold rules into
  collapsible, toggleable groups. The panel is a docked Compose surface (ADR-0021) whose match-set the host
  compiles and pushes to devices (ADR-0019).
- Decision:
  - **Model (`shared`, ADR-0013 pure/host-owned):** the layout is one ordered `List<MapLocalNode>` where a
    node is either a `RuleNode` (a loose, ungrouped rule) or a `GroupNode` (a `MapLocalGroup` + its ordered
    rules). Groups and loose rules **interleave** at the top level; there is exactly **one level** — a group
    never contains a group. This is deliberately a display+priority tree, not a flat list + parent pointers,
    so the on-screen order *is* the data order.
  - **Order is priority:** flattening the layout top-to-bottom (a group contributes its rules in place) yields
    the match order; the device already returns the first match in list order (ADR-0019), so
    `rulesForMatch()` emits the active rules in exactly that order and the first match wins. No priority field
    — position is the single source of truth.
  - **Group gates its rules (effective-enabled):** a rule is active only if its own switch is on **and** its
    group's switch is on (a loose rule has no group gate). Turning a group off deactivates its rules for
    matching while **retaining each rule's own on/off state**; in the list those child switches render
    *disabled* (via `CompactSwitch(enabled = …)`) but keep their remembered position, and the editor's toggle
    is likewise locked while the group is off. `compileRules`/`serveBody` both apply this effective-enabled so
    a rule in an off group never matches and never serves.
  - **Group lifecycle:** groups are **named** (required, non-blank, defaulting to "New group" — mirroring the
    rule editor's own "name required, defaults to Untitled" validation added in this same change). Rename is
    **click-to-edit**: the header shows the name as a plain title (not a permanent text box, so a saved name
    looks saved) until it's clicked, which swaps in an inline field that autofocuses with the text selected
    and **commits on Enter or on click-away (focus loss), reverting on Esc**; a blank name coerces to
    "New group" on commit. Adding is **two icon-only header buttons** next to Close — a "new mapping rule"
    (note-plus) and a "new group" (folder-plus) — each naming itself with a hover tooltip (the nav-rail
    tooltip style). This replaced a hand-rolled Material 3 FAB menu (a "+" that rotated into a close over a
    scrim with animated action pills): the real `FloatingActionButtonMenu`/`ToggleFloatingActionButton` are
    gated behind `@ExperimentalMaterial3ExpressiveApi`, still `internal` in Compose Multiplatform 1.11.0, and
    a from-scratch FAB menu was both heavy and a poor fit here — this is a docked desktop tool panel, where
    header actions read like a toolbar (what the surface actually is) rather than a phone-style FAB. "New
    group" creates an **empty** group and opens it straight into rename (so it can be named without a second
    click); rules are dragged in afterward, and **empty groups are allowed** (they persist).
    Deleting a group **deletes its rules too** (delete-all), behind a confirm dialog when the group is
    non-empty; an empty group deletes with no prompt.
  - **Drag-and-drop is hand-rolled — no new dependency.** A `LazyColumn` renders a flattened row list (group
    header, its indented child rows, a footer strip; or a single loose row). A drag handle per row runs
    `detectDragGestures`, accumulating **vertical** delta only; the drop target is resolved purely from
    vertical position against the live `LazyListState.layoutInfo` (row centers), and a 2 px insertion
    indicator previews the landing gap. Nesting is decided by *which container the gap falls in* — a gap after
    a group header or between its children (or on its footer strip) means "into the group"; a gap on a loose
    row or after a footer means "top level" — so no separate horizontal drag axis is needed. Both rules and
    whole groups are draggable anywhere in the one list. Move ops are pure (`moveRule`/`moveGroup`), resolved
    against the layout with the dragged item already removed, keeping indices consistent.
  - **Groups expand/collapse; groups and rules are visually distinct.** Each group header carries a caret
    (`ic_arrow_drop_down`, rotated) that folds its rules away and back. Collapsing is **transient view state**
    (a set of collapsed group ids) hoisted to `MapLocalManager`, *above* the panel's `NavDisplay` entries, so
    it survives opening a rule editor and returning but is intentionally **not persisted** — relaunch shows
    every group expanded (ADR-0013: the host owns the *data*; the viewer owns transient *view* state). The
    collapsed set feeds the single row-flattening (`toDispRows`), so a collapsed group's rules leave the
    rendered list *and* the drop hit-testing together; a collapsed header shows its rule count, and dropping a
    rule into a collapsed group auto-expands it so the rule can't silently vanish. For hierarchy, only the
    group **header** takes a fill (`surfaceVariant`, the anchor bar); **every rule row — loose or grouped —
    stays on the base `surface`**, so membership is never signalled by a fill. A grouped rule is set apart
    **only by its indentation** (`GroupChildIndent`) beneath the header bar — deliberately no rail, connector,
    or per-row line. That indent is sized so a child's switch lands directly under the group header's switch
    (the header's collapse chevron is what offsets them), so the toggles read as one aligned column. Earlier
    iterations tried progressively heavier cues and walked them all back: filling
    loose rows (`surfaceContainer`) read as two different card colors; a file-tree connector (a left-gutter
    spine with `├─` ticks and a closing `└─`), and then the same spine without the `└`, were both busier than
    the grouping warranted. Indent plus the header bar carry it. Note the
    grayscale token set (ADR-0016) defines only `surfaceContainer` in the container ramp — **not**
    `surfaceContainerHigh`/`Highest`/etc. — so reaching for an undefined role silently falls back to the
    Material 3 baseline's purple-tinted surface; the header therefore uses `surfaceVariant` (a defined token),
    never a container-ramp step the theme doesn't populate.
  - **Persistence (`MapLocalLayoutCodec`, in `shared` so it is pure + unit-tested):** the whole layout
    serializes to the host's primitive key-value store as one line per entry, `|`-separated, each free-text
    field Base64-encoded so it can't collide with delimiters. Line kinds: `G` group header, `C` a child rule
    of the current group, `R` a loose rule; a group's children are the contiguous `C` lines after its `G`, so
    the prefix alone rebuilds the interleaved tree. **Migration:** a line with no recognized prefix is the
    pre-groups format (rule id led the line) and decodes as a loose rule, so existing prefs load unchanged
    (extends the field-growth pattern of ADR-0019/0021).
  - **Host seam stays one callback:** the panel is still stateless over its inputs (ADR-0013/0021).
    `MapLocalManager`/`WailoApp`/`WailoViewer` now take `nodes: List<MapLocalNode>` + a single
    `onLayoutChange(List<MapLocalNode>)` (replacing the old `mapLocalRules` + `onUpsertRule`/`onRemoveRule`
    pair); every structural change — reorder, group toggle/rename/delete, rule add/edit/delete/move, rule
    toggle — is computed with the pure ops in `shared` and handed back as a whole new layout. The host
    (`Main.kt`) owns persistence, diffs old-vs-new to sweep orphaned body files (`reconcileRemovedBodies`),
    and recompiles the device match-set on every change.
- Alternatives considered:
  - **Flat list + a `groupId` on each rule.** Rejected: it re-derives display order and forces a parallel
    ordering field; the interleaved node tree makes "screen order == data order == priority" structural.
  - **Nested/arbitrary-depth groups.** Rejected as scope the ask explicitly excluded ("only 1 level") and a
    large DnD/priority-flattening complexity increase for no stated need.
  - **A drag-and-drop library (e.g. reorderable/Sticky).** Rejected: adds a dependency to the `studio` build
    for a bounded, single-list interaction; the hand-rolled version is a few pure helpers + one gesture and
    keeps the module dependency-light (mirrors the SDK's "small, dependency-light" ethos).
  - **Group off = rules forced off (mutate their state).** Rejected: it loses the user's per-rule intent; the
    ask was to *retain* each rule's state and only disable its control while the group is off.
- Consequences:
  - Rules can be organized into named, single-level groups; a group's switch toggles its whole set for
    matching without disturbing the members' own states; and the list reads top-to-bottom as match priority.
  - The device/wire contract is unchanged (ADR-0019): the host still pushes an ordered active-rule set and
    still serves bodies lazily; groups/priority live entirely host-side and collapse to that ordered set.
  - Old prefs migrate transparently (loose rules); a group delete removing its rules is intentional and
    confirmed. Empty groups persist.
  - Added two mono vector drawables (`ic_note_add`, `ic_create_new_folder`, Material Symbols Rounded) for the
    header add actions, and reused `ic_arrow_drop_down` (ADR-0021) as the group expand/collapse caret — all
    tint-driven, light/dark-safe per ADR-0016.
  - Collapse is per-session view state only (not stored): it survives in-panel navigation but reopening the
    app shows every group expanded.
  - Manual smoke (two-tier verification): hover the two header add buttons and confirm each shows its tooltip
    ("New mapping rule" / "New group"); add both and confirm a new group opens straight into rename with the
    text selected, and that Enter, click-away, and Esc each end the edit as expected (Esc reverts); click a
    group's caret and confirm its rules fold away (header then shows a rule count) and unfold, that the
    collapsed state survives opening a rule's editor and returning, and that it resets on app restart; drop a
    rule onto a collapsed group and confirm it auto-expands; drag a loose rule into a group and back out;
    reorder two rules and confirm the top one wins when both patterns match the same request; toggle a group
    off and verify (a) its child switches read disabled but keep their positions, (b) those rules stop
    mocking, and (c) turning the group back on restores each rule's prior state; rename a group, blank it and
    confirm it reverts to "New group"; delete a non-empty group and confirm the prompt + that its rules are
    gone; drag a whole group above/below another; restart the app and confirm groups, order, and toggles
    survive; check light/dark parity of the tinted group header and the indented child rows, and the drag
    indicator (ADR-0016), confirming a grouped rule reads as clearly inside its group (via indent + header)
    while sitting on the same base surface as a loose rule.

## ADR-0027: Interactive breakpoints — pause a matching request/response on-device, edit it live on the desktop, resume-or-abort (iOS-first; realizes ADR-0019's anticipated extension)

- Status: Accepted; building. `sdk-ios` `swift test` green (24 tests): `WailoBreakpointTests` covers store
  matching (enabled + at-least-one-phase, method filter, wildcard), request-phase abort → `URLError.cancelled`,
  `.proceed(nil)` fail-open onto the real network, and Map Local precedence; `WailoClientLoopbackTests` adds the
  `BreakpointRules`→`BreakpointRulesAck` anti-entropy round-trip, a `BreakpointHit`→`BreakpointDecision`
  resolution carrying an edited request, and disconnect fail-open (`.proceed(nil)`). Engine
  `WailoEngineBreakpointTest` added (rule push/ack, hit → `pausedExchanges`, resume/abort routed to the origin
  session, drop-on-disconnect); studio side verified via `ReadLints` + the hot-reload loop per the sandbox rule
  (studio/`gradlew` is unreliable inside the agent sandbox). Android deferred.
- Context: Map Local (ADR-0019) mocks a matched response *automatically* — no human in the loop. Breakpoints are
  the human-in-the-loop counterpart the reference proxy tools call "breakpoints": pause a
  matching request *before it is sent* and/or its response *before the app sees it*, let a person inspect and
  edit it live, then resume (optionally with edits) or abort. ADR-0019 explicitly built its bidirectional
  correlation/pending-map channel anticipating this ("the same correlation/pending-map machinery breakpoints
  will need — built once here"); this ADR realizes it. Three forces shaped the design: (1) the engine must stay
  headless (invariant #2) — it cannot block on a UI decision, so paused state has to be a seam the UI drives;
  (2) the SDK ships inside third-party apps (invariant #3) and must never *hang* an app's call on a desktop that
  has gone away; (3) consistency across the two SDKs is kept only by the wire contract (invariant #4), so the
  whole model must be expressible in `protocol`.
- Decision:
  - **Rule-based, matched on-device like Map Local** (not a global "pause everything" toggle — matches the
    reference tools and reuses ADR-0019's mental model). A `BreakpointRule` = `{id, enabled, url_pattern,
    methods[], on_request, on_response}`; a rule with neither phase set never fires. The match decision stays
    local and instant (no per-request desktop round-trip), exactly as ADR-0019 argued for Map Local.
  - **Four wire messages on the existing `Envelope` oneof (ADR-0008), fields 9–12:** `BreakpointRules{rules[],
    epoch}` + `BreakpointRulesAck{epoch}` (desktop→device snapshot + ack — the *identical* anti-entropy as
    `RuleSet`/`RuleAck`); `BreakpointHit{correlation_id, rule_id, phase, HttpRequest, HttpResponse}`
    (device→desktop: a matched exchange is paused — `request` always set, `response` only for the RESPONSE
    phase); `BreakpointDecision{correlation_id, action, edited_request, edited_response}` (desktop→device:
    resume-with-optional-edits or abort). `enum BreakpointPhase{REQUEST=0, RESPONSE=1}` and
    `enum BreakpointAction{PROCEED=0, ABORT=1}` — PROCEED is 0 so a malformed/empty decision fails **safe** (the
    call continues) rather than aborting the app's request.
  - **Indefinite hold while connected; fail-open only on disconnect.** This is the one deliberate deviation from
    Map Local's 10 s `bodyTimeout` (ADR-0019): a human is editing, so there is **no** timeout. The app's
    `URLProtocol` load is held open (non-blocking) until a `BreakpointDecision` arrives or the link drops. On
    disconnect the device drains every held request/response as `.proceed(nil)` — it makes its own real network
    call with the *original* message — so a desktop that quits or crashes can never wedge the host app. Same
    "we are not a proxy; the SDK always owns the real request" stance as ADR-0019's fail-open.
  - **Abort = fail the app's call with `URLError(.cancelled)`** device-side; the mirrored exchange is flagged
    `edited` so the desktop list marks it intercepted rather than a genuine network failure.
  - **Precedence v1: a Map Local match short-circuits and is never broken.** Breakpoints apply only on the
    real-network path; a URL matching both is served by Map Local and never handed to the gate. This sidesteps
    the request-edit-then-rematch tangle (would an edited request re-run Map Local matching?) — documented and
    revisitable.
  - **Engine stays headless via a paused-state seam.** `WailoEngine` exposes
    `pausedExchanges: StateFlow<List<PausedExchange>>` and mirrors the Map Local push path
    (`updateBreakpointRules` + `pushBreakpointRules(session)` + `SessionState.ackedBreakpointEpoch` +
    `reconcile()` retry). On a `breakpoint_hit` it appends a `PausedExchange` and records which session owns each
    `correlation_id` (a `ConcurrentHashMap`); `resumeBreakpoint(id, editedRequest?, editedResponse?)` /
    `abortBreakpoint(id)` send the `BreakpointDecision` on the owning session and drop the row; on session close
    it drops that session's rows (unresolvable once the device is gone). The engine never blocks — the decision
    comes later from the UI.
  - **iOS device (`sdk-ios`):** `WailoBreakpointStore` caches the pushed rules and answers
    `match(url, method) -> (ruleId, onRequest, onResponse)?` (dropped on disconnect, like `WailoRuleStore`).
    `WailoClient` handles inbound `breakpoint_rules` (replace + ack) and `breakpoint_decision` (resolve pending)
    and *is* the `WailoBreakpointGate` — a pending map keyed by a client-minted `correlation_id`, **no timeout
    timer**, drained `.proceed(nil)` on disconnect. `WailoURLProtocol` pauses the request phase in
    `startLoading` (after the Map Local check) and the response phase in `finish` (before forwarding to the
    client), applying edits, aborting via `didFailWithError`, or falling open.
  - **Desktop UI:** a third docked tool panel `BreakpointManager` (rules CRUD, mutually exclusive with Map
    Local / Capture Allowlist per ADR-0021) plus a modal `BreakpointEditor` overlay shown when `pausedExchanges`
    is non-empty — `UnderlineTabs` (Headers/Body) over the editable `CodeEditor` (ADR-0023) + editable
    method/URL (request) or status (response) + Resume/Abort. **One paused item is shown at a time**; concurrent
    hits queue behind it. Rules persist host-side (`BreakpointStore`, primitive KV with Base64 fields like
    ADR-0019/0026), the host pushes on change via `LaunchedEffect`, and paused rows are bridged engine→`shared`
    at the module boundary (a `PausedFlow`, like `FlowEntry`) so `shared` never depends on `engine` (ADR-0013).
- Alternatives considered:
  - **A global pause toggle** (break everything): rejected — noisy and unlike the reference tools; rule-based
    reuses Map Local's matching and mental model.
  - **A short hold timeout** (like Map Local's 10 s): rejected — a human is deciding; a timeout would abort
    mid-edit. Fail-open is reserved for the disconnect (no-authority) case only.
  - **Break Map Local matches too (v1):** rejected for the edit-then-rematch ambiguity; deferred behind the
    documented precedence.
  - **Queue *and* show all concurrent hits at once:** rejected for v1 — one-at-a-time keeps the editor
    unambiguous; extra hits wait behind the current one.
- Consequences:
  - The bidirectional control channel ADR-0019 built now carries a second RPC (hit/decision) with **no new
    transport machinery** — the correlation/pending-map pattern paid off exactly as predicted.
  - A held call depends on the desktop staying connected; the instant it is not, the call proceeds with its
    original bytes — never hangs. Paused state is not persisted across a disconnect (consistent with
    ADR-0003/0025's no-backlog stance).
  - **Android does not break** (it still ignores inbound frames, ADR-0019); when it does it must mirror this
    model over the same wire contract (metadata cache, on-device match, indefinite-hold/fail-open, ack), since
    only the contract keeps the SDKs aligned (invariant #4).
  - Added an `ic_breakpoint` mono vector drawable (tint-driven, light/dark-safe per ADR-0016).
  - Manual smoke (two-tier verification): with desktop + `sample-ios` connected, add a request breakpoint and
    trigger traffic → the editor opens paused; edit URL/headers/body and Resume → the app receives the edited
    request; add a response breakpoint, edit status/body and Resume → the app sees the edited response; Abort →
    the app's call fails (`.cancelled`); with a hit paused, quit the desktop → the app's call completes against
    the real network (fail-open). Smoke the panel + editor in both light and dark.

## ADR-0028: Breakpoint rules gain Map Local's groups by generalizing the layout core into a shared generic (both panels are one grouped, drag-orderable list)

- Status: Accepted; building. `:shared:jvmTest` green (`MapLocalLayoutTest` unchanged in behavior — its raw casts were rewritten to typed helpers since the node types are now generic — plus a new `BreakpointLayoutTest`: priority/flatten + group-gating over `rulesForMatch`, move-into-group, codec round-trip, and legacy flat-format migration); studio compiles (`:shared:compileKotlinJvm`, `:desktopApp:compileKotlin`). Verified via `ReadLints` + these compiles per the sandbox rule (studio `gradlew` is unreliable inside the agent sandbox).
- Context: ADR-0026 gave Map Local single-level groups, order-as-priority, group-gating, collapse, and hand-rolled drag-and-drop; ADR-0027 shipped breakpoints as a flat rule list with an inline add/edit form. The two rule panels are now the *same* interaction, and breakpoints should get grouping "like Map Local" — without duplicating the ~880 lines of layout model + drag-drop list UI (which would inevitably drift).
- Decision:
  - **Generalize the layout model (`shared/RuleLayout.kt`):** an F-bounded `LayoutRule<T : LayoutRule<T>>` exposing only what the pure ops need — `id`, `enabled`, and `withEnabled(enabled): T` (so a rule can be toggled without `.copy` on an unknown type) — plus a rule-agnostic `RuleGroup`, `LayoutNode<T>`/`RuleNode<T>`/`GroupNode<T>`, `LayoutDropTarget`, and all the read/mutation ops (allRules/findRule/groupOf/rulesForMatch/upsert/remove/move/…). `MapLocalRuleDef` and `BreakpointRuleDef` both `implement LayoutRule`; `MapLocalNode`/`MapLocalGroup` and `BreakpointNode` are thin typealiases so each panel's call sites still read in its own terms. Only the persistence codec stays per-feature.
  - **Generalize the list UI (`shared/ui/DraggableRuleList.kt`):** `GroupedRuleListPage<T>` owns the whole list page — top bar (title + add-rule + new-group + Close), a one-line description, empty state, the drag/drop/collapse/group-header/footer/drop-indicator machinery, and the delete-group confirm. The *only* per-feature difference is a `ruleContent(rule)` slot for the row's clickable label; the drag handle, enabled switch, and delete affordance are shared. Map Local's `RuleListPage` and the breakpoints panel are now thin callers.
  - **Breakpoints adopt the list→editor shape.** `BreakpointManager` shows the grouped list and swaps to a compact rule-editor page (URL pattern + method + Request/Response phases, with a header enabled switch that — like Map Local — commits immediately for a persisted rule and edits the draft for an unsaved one, and is gated when the rule sits in an off group). This replaces the old inline add/edit form, which doesn't compose with a nested, drag-orderable list.
  - **Persistence + compile mirror Map Local.** `BreakpointLayoutCodec` encodes the layout as `G`/`C`/`R` lines with Base64 fields (same shape as `MapLocalLayoutCodec`); a pre-groups flat line (no prefix) migrates as a loose rule, and the prefs key is unchanged so existing breakpoint rules load. `compileBreakpointRules(nodes)` now ships `rulesForMatch()` — active rules (group-on AND rule-on) in priority order — instead of every rule with an `enabled` flag, so a rule in an off group is simply not pushed.
  - **Concise descriptions (the other half of the ask):** both panels show a one-sentence caption under the header — Map Local gained one ("Answer matching requests with a local response instead of hitting the network."), and the breakpoints copy was trimmed from a paragraph to one line.
- Grouping is a **desktop-only organizational concept**: the device still receives a flat, ordered *active*-rule list, so there is **no** protocol/engine/iOS/`sdk-*` change — the wire contract (invariant #4) is untouched, and Android is unaffected.
- Alternatives considered:
  - **Duplicate the model + drag-drop list for breakpoints:** rejected — ~880 lines that would drift; the panels are genuinely one interaction, so a generic is the honest shape.
  - **Keep the breakpoint inline form and bolt groups on:** rejected — an inline editor doesn't fit a grouped, nested, drag-orderable list (Map Local moved to a push editor for exactly this reason).
  - **Ship group-off/disabled breakpoint rules with a flag (ADR-0027's original compile):** rejected in favor of `rulesForMatch()`, so group-gating is enforced by *what is pushed* with no device-side change.
- Consequences:
  - One generic seam (`RuleLayout` + `DraggableRuleList`) now backs both panels; a third rule panel would be a `GroupedRuleListPage<T>` + a codec + a row slot. Because the node types are generic, `as GroupNode`/`filterIsInstance<GroupNode>` no longer compile without a type argument — `MapLocalLayoutTest` was switched to the typed `findRule`/`groupNode` helpers, and a bare `listOf(RuleNode(…), GroupNode(…))` needs an explicit `List<…Node>` type (Kotlin's LUB otherwise star-projects the F-bound).
  - `WailoApp`/`WailoViewer`/`Main` thread `breakpointNodes`/`onBreakpointLayoutChange` (a layout) instead of a flat rule list, matching the Map Local wiring.
  - Manual smoke (two-tier verification): open Breakpoints → add rules, add a group, rename it, drag rules in/out and reorder, collapse, and toggle the group off (its child switches read disabled) → the paused editor still fires only for active rules and honors priority; confirm the one-line description renders on both Map Local and Breakpoints in light and dark.

## ADR-0029: Capture allowlist/blocklist — a device-side, whole-exchange capture filter replaces the per-host "unlock bodies" model (supersedes the informal Capture Allowlist)

- Status: Accepted; building. Engine `WailoEngineLoopbackTest` updated (capture-filter connect-time snapshot + epoch, and unacked-repush / acked-no-repush anti-entropy, mirroring the rule-set tests). iOS `WailoSDKTests` rewritten to cover `WailoCaptureFilterStore.shouldCapture` (allowlist-only, blocklist-only, both-combined, enabled-but-empty, reset→capture-everything) and `capturedBody`'s cap-only truncation; `WailoClientLoopbackTests` drop the removed `bodies_omitted` field. The generated Swift protobuf (`CaptureFilter`/`CaptureFilterAck`/`Envelope`/`HttpExchange`) was hand-edited to mirror the Wire codegen and must be regenerated by `:protocol:generateSwiftProto` (the seam: `:protocol:publishToMavenLocal` before the studio build). Studio verified via `ReadLints` + the hot-reload loop per the sandbox rule (studio `gradlew` is unreliable inside the agent sandbox). Android unaffected (never implemented the allowlist).
- Context: The prior "Capture Allowlist" (never formally ADR'd) was a *per-host body gate*: metadata (method/url/status/headers/sizes) was **always** captured and streamed, and only the request/response **bodies** were withheld unless the host was "unlocked", each withheld exchange flagged `HttpExchange.bodies_omitted` so the desktop could offer to unlock. The ask reframes the feature around *which traffic you want to see at all*: rename local/unlock to **allowlist/blocklist**, make each list independently on/off-able, and drop the metadata-only middle ground — an exchange is captured **entirely or not**. Three clarifications (grill) fixed the shape: **filtering is device-side** (the desktop pushes config; the device only sends matching exchanges), **items match the request host** with a `*` wildcard (as the old allowlist did), and **items are remove-only** — a list is added to via a `+` on its section header, never a per-item switch. Forces: the SDK ships in third-party apps and must stay small/dependency-light (invariant #3); `protocol` is the only cross-SDK seam (invariants #1/#4); the engine is headless and must not re-filter (invariant #2); the UI is theme-first and stateless-over-inputs (ADR-0013/0016).
- Decision:
  - **Whole-exchange, device-side gate.** The device decides per request whether to capture **the entire exchange** (headers + both bodies) or nothing; a filtered host is dropped **at the source** and never streamed. There is no partial/metadata-only capture, so `HttpExchange.bodies_omitted` is deleted (`reserved 8`) and the interceptor's per-host body gating (`capturedBody(allowed:)`) collapses to a size-cap-only helper.
  - **Two independent lists, each with its own switch.** `allowlist_enabled` ⇒ capture only hosts matching `allow_patterns`; `blocklist_enabled` ⇒ never capture hosts matching `block_patterns`; **both on** ⇒ a host must be allowed **and** not blocked (the blocklist carves exceptions out of the allowlist); **both off** (the default) ⇒ capture everything. An **enabled list with no patterns is inert** — an enabled-empty allowlist matches nothing (captures nothing), an enabled-empty blocklist blocks nothing — so a list can never silently flip to "all" or "none". Patterns match the request **host** (not the full URL), `*` = any run of characters, case-insensitive — the same wildcard scheme as Map Local (`WailoRuleStore`) and the old allowlist, so device and desktop agree.
  - **Wire (invariant #1/#4):** `CaptureAllowlist{host_patterns[], epoch}` + `CaptureAllowlistAck` are replaced by `CaptureFilter{allowlist_enabled, allow_patterns[], blocklist_enabled, block_patterns[], epoch}` + `CaptureFilterAck{epoch}` on the **same** `Envelope` oneof fields 7/8 (ADR-0008). Delivery is identical anti-entropy to `RuleSet`/`RuleAck` (ADR-0019): a full snapshot pushed on connect and on every edit, versioned by `epoch`, re-pushed until the device acks, and applied on receipt regardless of epoch (a desktop restart that resets the counter is harmless).
  - **Engine stays a relay (invariant #2).** `WailoEngine` holds `captureFilter: StateFlow<CaptureFilter>`, exposes `updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)`, and pushes/reconciles it per-session with its own `captureFilterEpochCounter` + `SessionState.ackedFilterEpoch` (mirroring the rule path). It **never re-filters** — `record()` stores whatever a device still sends; the gate lives only on the device.
  - **Device gate at `emit()` (iOS).** `WailoCaptureFilterStore` caches the pushed `CaptureFilter` and answers `shouldCapture(host:)`; `WailoURLProtocol.emit` drops a filtered host's exchange before building it (and `startLoading` skips draining the request-body stream for a filtered host with no breakpoint, keeping memory bounded to hosts that pass). The gate is uniform across sinks, so the `ConsoleSink` mirror respects the filter too. Breakpoint interception is unaffected — a hit still rides the control channel (not `emit`), so you can inspect/edit even a filtered host at a breakpoint — but the resulting **passive** traffic row, like a Map Local-served response, respects the filter. Deliberate v1 choice: "the filter decides what shows up in the traffic log."
  - **Default = capture everything; disconnect resets to it.** The device store defaults to both-off and `reset()`s to both-off on disconnect (the desktop is the source of truth; no user-authored list outlives the connection, like Map Local's drop-cached-on-disconnect). The engine re-pushes on every connect, so a real filter reasserts within one frame of reconnect. This keeps the pre-push / no-desktop path permissive so the `ConsoleSink` (the M1 local aid) still prints, and nothing streams while disconnected anyway (live tap, ADR-0025), so the only exposure is a sub-frame reconnect gap — accepted over silencing the no-desktop path.
  - **UI: a two-section panel modeled on Map Local groups (ADR-0021/0026), no drag.** The docked right tool panel `CaptureFilterManager` shows an **Allowlist** section over a **Blocklist** section; each is a header band (its on/off switch + a `+` that reveals an inline, validate-on-commit add field) over its host rows (host + delete). There is **no drag** and **no per-item switch** — the section's single switch arms the whole list. A section switch is **disabled while its list is empty** ("empty ⇒ off"). The traffic row context menu's old "Unlock" becomes two independent **Allowlist**/**Blocklist** checked toggles for the row's exact host.
  - **State + persistence (ADR-0013).** `CaptureFilterState` (in `shared`) is the host-owned value with pure `addAllow/removeAllow/addBlock/removeBlock/setAllowEnabled/setBlockEnabled` helpers that keep the "empty ⇒ switch off" invariant; `shared` is stateless over it and hands back a new value per change. `CaptureFilterStore` (desktop, primitive KV like the Map Local layout) persists each list as one newline-delimited string + a boolean per switch, and **re-asserts the invariant on load** so a hand-edited/partial pref can't return an enabled-but-empty list. `Main.kt` owns it and pushes via `LaunchedEffect` → `engine.updateCaptureFilter`.
- Alternatives considered:
  - **Desktop-side filtering** (device streams all, desktop hides): rejected per the ask — wastes device battery/bandwidth streaming traffic the user has excluded, and leaks it off-device; device-side drop-at-source is the point.
  - **Keep metadata-only capture for filtered hosts** (the old `bodies_omitted` model): rejected — the ask is explicitly all-or-nothing ("entire or not"); a metadata middle ground is exactly what's being removed.
  - **Per-item enable switches** (toggle individual hosts): rejected per the grill — one switch per list; items are add/remove only, keeping the section the unit of arming.
  - **Reuse the grouped, drag-orderable `GroupedRuleListPage` (ADR-0028):** rejected — there's no priority or grouping here (two fixed sections, membership not order), and the ask says no drag; a purpose-built two-section list is simpler and honest.
  - **Gate only the desktop sink, leave `ConsoleSink` unfiltered:** rejected — a single uniform gate at `emit` is simpler and matches "the filter decides what is captured"; the permissive default already keeps the no-desktop console working.
- Consequences:
  - One filter now governs *whether traffic is captured at all*, per host, decided on-device; the desktop authors it and the engine merely relays + records. Bodies are captured in full whenever a host passes (subject only to the optional `maxBodyBytes` cap), so the desktop no longer shows an "unlock to see bodies" affordance.
  - **Breaking wire change:** the oneof fields 7/8 change type. Both SDKs and the engine move together; an old device talking to a new desktop (or vice versa) would see an unknown/mismatched control message on those tags. Acceptable pre-1.0 (invariant #4 keeps them aligned by the contract, and all first-party consumers are updated in lockstep).
  - **Android does not break** (it ignores inbound control frames, ADR-0019/0027); when it adopts capture filtering it must mirror this whole-exchange model over the same contract.
  - Dead code removed across the stack: `HttpExchange.bodies_omitted`, `FlowEntry.bodiesOmitted`, `isHostUnlocked`/`hostMatchesPattern`, `CaptureAllowlistManager`/`CaptureAllowlistStore`, iOS `WailoCaptureConfigStore`/`isBodyAllowed`, and the DetailPanel "bodies omitted / unlock" notice.
  - Manual smoke (two-tier verification): with desktop + `sample-ios` connected and both lists off, confirm all traffic is captured. Add a host to the Allowlist and turn it on → only that host's exchanges appear (others vanish at the source, not greyed out); confirm `*.example.com` covers subdomains and a bare `example.com` does not. Turn the Allowlist off, add a host to the Blocklist and turn it on → that host disappears, everything else remains. Turn both on with the same host allowed and a subpath/subdomain blocked → the blocked host is excluded while the rest of the allowlist shows. Empty a list by deleting its last host → its switch snaps off and disabled. Use a traffic row's context menu to add/remove its host from either list and confirm the panel reflects it. Restart the desktop → lists, order, and each switch survive, and a hand-broken pref (enabled but empty) loads as off. Quit the desktop → `sample-ios` keeps printing to its console (permissive default). Check light/dark parity of the two section header bands, host rows, add field, and the disabled-switch state (ADR-0016).

## ADR-0030: Per-feature master switch — one on/off above each interception feature (capture filter, Map Local, breakpoints), gating what's pushed, never the saved state

- Status: Accepted; building. Pure studio change — no `protocol`/wire change and no SDK change (the master reuses paths a device already handles: an empty `RuleSet`/breakpoint set means "nothing matches", both-lists-off means "capture everything", ADR-0019/0029). Verified via `ReadLints` + the hot-reload loop per the sandbox rule (studio `gradlew` is unreliable inside the agent sandbox). Android/iOS unaffected.
- Context: All three interception features already had per-item switches — and, for the capture filter, per-list switches (ADR-0029) and, for Map Local/breakpoints, per-rule and per-group switches (ADR-0026/0027) — but **no single "turn the whole feature off" control**. The ask: one switch per feature that disables *all* its items at once, and — crucially — leaves each item's own state intact so flipping the master back on restores exactly what was armed. Forces: the engine is a headless relay (invariant #2); `shared` is stateless over host-owned state (ADR-0013); the device should not need to change (the master should be a desktop-side gate above the existing push, not a new wire concept, invariants #1/#4).
- Decision:
  - **The master gates what the host *pushes*, not the persisted state.** Off ⇒ the host pushes an empty rule-set (Map Local / breakpoints) or both-lists-disabled (capture filter); the saved layout/filter is untouched. It sits one level *above* group-gating (ADR-0026) and `rulesForMatch()`: even individually-enabled rules are withheld while the master is off, and every per-rule/group/list state is preserved for when it flips back on. Non-destructive by construction, so it round-trips.
  - **No wire/SDK change.** The device already treats an empty `RuleSet`/breakpoint set as "nothing matches" and both-lists-off as "capture everything" (ADR-0029), so the master reuses those paths — the three anti-entropy pushes (ADR-0019) simply carry the gated snapshot. Invariants #1–#4 untouched; the engine stays a relay.
  - **UI: a single `CompactSwitch` heading each panel header's right-side actions** (before add-rule / new-group / close — the leftmost of the right cluster, so it reads as a control over the panel, not a list row). Map Local and Breakpoints get it via the shared `GroupedRuleListPage`; the capture filter via `CaptureFilterManager`. Off dims the list/sections (`DisabledFeatureAlpha`) and disables every per-rule / per-group / per-list switch (their remembered `checked` state kept), and gates the rule editors' enable toggle too — so a master-off item reads disabled exactly the way a rule in an off group already does (ADR-0026), one consistent "disabled but not erased" idiom. Rules stay addable/editable/reorderable while off (you can curate a disarmed feature); only the *enable* toggles are frozen.
  - **Live toggling through Map Local's cached nav (ADR-0022).** Map Local's panel is a `NavDisplay` whose entries are cached and only recompose when they read *changed snapshot state*; the layout already dodges this by reading `nodes` through a `rememberUpdatedState` (`liveNodes`). The master is read the same way (`liveEnabled`) so toggling it recomposes the cached list/editor immediately, instead of appearing stuck until the next navigation. Breakpoints/capture filter render directly (no nav cache), so they need no such indirection.
  - **State + persistence (ADR-0013).** The capture filter's master rides inside `CaptureFilterState.masterEnabled` (its `*Store` already persists that object). Map Local's and breakpoints' masters are separate host-owned booleans persisted by `MapLocalStore`/`BreakpointStore`, because their layouts are a bare `List<LayoutNode>` with no single object to host the flag. All three default **on**, so an install with no saved value behaves exactly as before the switch existed. `Main.kt` owns the three flags and gates each `LaunchedEffect` push on its master (Map Local also empties `ruleDefsRef`, so an in-flight body match can't be served while off).
- Alternatives considered:
  - **Clear the per-item switches when the master goes off** (instead of gating the push): rejected — it erases the user's arming; the whole point is that the master is non-destructive and restores prior state on re-enable.
  - **A device-side master flag on the wire:** rejected — unnecessary and would touch `protocol` + both SDKs (invariants #1/#4) for zero behavioral gain, since an empty push already means "off".
  - **Reuse "both lists off" as the capture-filter master (no new `masterEnabled`):** rejected — it loses each list's armed state and offers no single visual "off"; a dedicated master disables both at once while preserving per-list state.
  - **One global master (a rail/toolbar toggle) across all three features:** rejected — the ask is per-feature; the tool rail already opens/closes panels, and a per-panel header switch keeps each feature's on/off co-located with its own controls.
  - **A `masterEnabled` field bolted onto the Map Local/breakpoint layouts:** rejected — those are `List<LayoutNode>`, not a wrapper object; a separate persisted boolean is cleaner than inventing a layout envelope just to carry one flag.
- Consequences:
  - Each feature now has one switch that makes it wholly inert without losing its configuration; turning it back on resumes the exact prior arming (rules, hosts, group/list switches).
  - No protocol/SDK change; Android/iOS unaffected (they already handle empty pushes / both-off). The gate is purely desktop-side, so the engine stays a relay (invariant #2).
  - The "disabled but remembered" idiom now spans three levels — feature master ⊃ group ⊃ rule — all rendered the same way (disabled switch, state kept), so the UI reads consistently.
  - Manual smoke (two-tier verification): for each feature, with desktop + `sample-ios` connected, arm some rules/hosts, then flip the master **off** → the panel dims, every switch goes disabled but keeps its checked state, and the device behaves as if the feature were empty (Map Local falls through to the network, no breakpoint pauses, all traffic is captured). Flip the master **on** → the exact prior arming resumes with no re-editing. Toggle an individual rule/list while the master is off → it's non-interactive. Restart the desktop → each master's state survives, and a fresh install defaults to on. Check light/dark parity of the dimmed list/sections and the disabled switches (ADR-0016).

## ADR-0031: Android reaches device-side parity — Map Local, breakpoints, and the capture filter ported from iOS over the existing wire contract (realizes the deferrals in ADR-0019/0027/0029)

- Status: Accepted; building. `:sdk-android:testDebugUnitTest` green: `WailoRuleStoreTest` / `WailoBreakpointStoreTest` / `WailoCaptureFilterStoreTest` cover the three stores' matching (enabled + method filter + URL/host wildcard, at-least-one-phase, allow/block/both/enabled-empty/reset); `WailoInterceptorTest` drives the blocking interceptor with a fake `Chain` + fake control channel (capture-filter drop, Map Local serve + fail-open, breakpoint request abort/edit, response edit/resume-unchanged); `WailoClientControlTest` is the loopback (mirrors iOS `WailoClientLoopbackTests`): `RuleSet`/`CaptureFilter`/`BreakpointRules` → their acks, a `BodyRequest`→`BodyResponse` fetch, a `BreakpointHit`→`BreakpointDecision` hold, and fail-open both when never connected and when the link drops mid-hold. The existing streaming `WailoClientTest` still passes (the rewrite preserved the live tap). Verified by a local `./gradlew :sdk-android:testDebugUnitTest` (the remote `rgradlew` server was unreachable; the SDK build is the repo-root Gradle 8.11.1, not studio, so it runs cleanly outside the sandbox).
- Context: ADR-0019 (Map Local), ADR-0027 (breakpoints), and ADR-0029 (the capture filter) shipped the whole desktop→device control channel on the engine, the desktop UI, and `sdk-ios`, but each deferred Android: the Android SDK was capture-and-stream only (`WailoClient` literally `consumeEach {}`-ignored every inbound frame). Each ADR recorded the same standing instruction — "Android does not break (it ignores inbound frames); when it does it must mirror this model over the same wire contract, since only the contract keeps the SDKs aligned (invariant #4)." This ADR is that work: full device-side parity with iOS. The protocol already carries every message (fields 3–12 of the `Envelope` oneof, ADR-0008), so this is a *consumer* addition on one side of an existing contract.
- Decision:
  - **No protocol / engine / desktop change (invariants #1/#4).** Android now speaks the device half of a contract the desktop already speaks; the engine's anti-entropy push/ack, the Map Local correlation/body-fetch, and the breakpoint hit/decision RPC are all reused unchanged. The two SDKs stay aligned only by the wire contract, never by shared code (invariant #4).
  - **Three process-global stores mirror the iOS `.shared` singletons:** `WailoRuleStore` (Map Local match-metadata), `WailoBreakpointStore` (+`Match`), `WailoCaptureFilterStore` (`shouldCapture(host)`), each a `@Volatile`-published immutable snapshot (single writer = the socket loop; readers = arbitrary OkHttp threads) dropped/reset on disconnect so the desktop stays the single source of truth. Global rather than injected because the interceptor is built per OkHttp client and usually wraps a *composite* sink that hides the transport — the same reason iOS uses `WailoURLProtocol` statics, and consistent with the existing process-global `WailoRuntime.sink`. The wildcard/method matcher lives in one seam (`WailoMatching`) instead of iOS's per-store duplication (Kotlin lets us share it; the scheme — `*` = any run, else literal, whole-string anchored, host match case-insensitive — is identical so device and desktop agree).
  - **Inbound plumbing in `WailoClient`:** decode each `Envelope`, dispatch the oneof, apply a snapshot to its store and ack its epoch (a lost push self-repairs via the engine's reconciler), resolve a `BodyResponse`/`BreakpointDecision` against a pending map. A connection now owns **two** outbound channels: the existing bounded, `DROP_OLDEST` exchange tap (a slow desktop must never OOM the app) **plus** a separate `UNLIMITED`, never-dropped control channel — an ack or a breakpoint hit must not be dropped under back-pressure. On every disconnect the cached snapshots are dropped and all in-flight fetches/holds are drained fail-open.
  - **The async→blocking translation is the crux.** iOS's `URLProtocol` is asynchronous (start a fetch, return, deliver later); OkHttp's `Interceptor.intercept` runs on a background call thread and **must return a `Response`**. So the control seam (`WailoBodyFetcher`/`WailoBreakpointGate`) is **blocking**, not callback-based: a Map Local match blocks the call thread on the body fetch with a 10 s timeout; a breakpoint hold blocks it with **no timeout** (a human is deciding). Both are bridged from the client's coroutines to the OkHttp thread by a `CompletableFuture` completed by the matching inbound frame or, on disconnect, by a fail-open drain (null). Registration inserts the future **before** reading the connection handle, and teardown nulls the handle **before** draining, so a fetch/pause racing a disconnect can never orphan — it always fails open. `WailoClient.start()` self-registers as the process-global control authority (and clears it on stop), mirroring iOS `Wailo.start` wiring the statics.
  - **Interceptor order mirrors iOS `WailoURLProtocol`:** capture filter first (whole-exchange, dropped at the source — a filtered host with no breakpoint passes straight through without buffering its body or emitting a row); then Map Local (precedence, never broken — serve a synthetic OkHttp `Response` from the desktop's bytes, fail open to the network on any miss); then, on the real-network path only, the breakpoint request phase (Abort → `IOException`, an edited request rebuilds the outgoing `Request`, a disconnect proceeds with the original) and the response phase (read the full body to show/replace, then rebuild the `Response` — original or edited). The mirrored exchange is flagged `edited` for a Map Local serve, an abort, and a response edit.
  - **One deliberate divergence from iOS:** a request-phase edit is flagged `edited=true` and the mirrored row shows the request *actually sent* (Android builds the edited `HttpRequest` into the real `Request`, so it has the accurate bytes), whereas iOS emits the original request unflagged there. This is wire-invisible — the desktop just displays whatever the device sends — and strictly more accurate, so it is not worth contorting the OkHttp path to reproduce the iOS limitation.
- Alternatives considered:
  - **Inject the stores/gate into the interceptor instead of process globals:** rejected — the interceptor is constructed per client and its sink is often a composite that hides the `WailoClient`, so there is no reliable construction-site to inject through; globals match iOS and the existing `WailoRuntime` model, and tests set them directly (like the iOS `defer { store.replace([]) }`).
  - **Bridge with `runBlocking` over the coroutine channels:** rejected in favor of `CompletableFuture` — a plain blocking `get`/`get(timeout)` on the OkHttp thread is simpler and avoids spinning an event loop per request; the completing side (a coroutine) just calls `complete`.
  - **Keep iOS's async callback shape for the gate:** rejected — OkHttp is blocking, so a linear blocking call reads better and matches the thread model; the "async" was only an artifact of `URLProtocol`.
  - **One merged outbound channel:** rejected — the exchange tap must drop-oldest to bound memory, but acks/hits must never drop; two channels keep both invariants.
- Consequences:
  - Android now has Map Local, breakpoints, and the capture filter at device-side parity with iOS, entirely over the existing contract — no engine/desktop/protocol edit, the same anti-entropy + correlation/pending-map + fail-open model on both SDKs.
  - **A held breakpoint blocks an OkHttp dispatcher thread** for the duration of the hold (inherent to OkHttp's blocking model, where iOS holds a `URLProtocol` load, not a thread). Acceptable: breakpoints are opt-in, rule-matched, and shown one at a time (ADR-0027). A host-configured OkHttp `callTimeout` will cut a long hold short (the call fails / falls open); connect/read/write timeouts don't apply during the pre-send request hold.
  - Bodies: the existing `Wailo.DEFAULT_MAX_BODY_BYTES` (256 KiB) cap still truncates the *mirrored* copy, and the capture filter bounds *which hosts* are mirrored at all; a response-phase breakpoint reads the full body into memory so it can be shown/replaced (opt-in, matched).
  - The device drops all cached snapshots on disconnect, so with no desktop the SDK is exactly the pre-parity capture-and-stream SDK (Map Local falls through, no breakpoints, capture-everything) — `LogcatSink` and offline use are unchanged.
  - Manual smoke (two-tier verification): with the desktop + `sample-android` connected — **Capture filter:** add a host to the Allowlist and enable it → only that host's exchanges appear (others vanish at the source); enable an empty list → inert; quit the desktop → capture-everything resumes. **Map Local:** add a rule matching a real request → the app receives the desktop's mocked status/headers/body and the row is flagged edited; disable/quit → the real network answers (fail open). **Breakpoints:** add a request breakpoint → the app's call pauses until Resume (edit URL/method/headers/body → the edited request is sent) or Abort (the call fails); add a response breakpoint → edit status/body and Resume; with a hit paused, quit the desktop → the call completes against the real network (fail open). Confirm a filtered host is still inspectable/editable at a breakpoint but produces no passive row.

## ADR-0032: Breakpoint request-edit re-runs Map Local (precedence v2) — an edited request that now matches a Map Local rule is served locally, not sent to the network (closes ADR-0027's deferred request-edit-then-rematch)

- Status: Accepted; building. No `protocol`/engine/desktop change — device-side match logic only, over the existing wire contract. iOS `WailoBreakpointTests` green (added `testRequestEditThatMatchesMapLocalIsServedLocally`; `swift test --filter WailoBreakpointTests` = 6/6). Android `:sdk-android:testDebugUnitTest` green (added `WailoInterceptorTest.breakpointRequestEditThatMatchesMapLocalIsServedLocally`), run locally outside the sandbox (the SDK build is repo-root Gradle 8.11.1; the agent sandbox can't run either wrapper).
- Context: ADR-0027 shipped **precedence v1** — "a Map Local match short-circuits and is never broken; breakpoints apply only on the real-network path" — and explicitly deferred one case: *"This sidesteps the request-edit-then-rematch tangle (would an edited request re-run Map Local matching?) — documented and revisitable."* In practice that deferral is surprising: Map Local is matched **once**, at entry, against the *original* URL. A request-phase breakpoint can then rewrite the URL/method, but the edited request was sent straight to the network even when it now matched a Map Local rule — so editing a paused request's URL to a mapped one behaved differently from the app simply issuing that URL (which *would* hit Map Local). This ADR closes that gap.
- Decision:
  - **Re-run Map Local after a request-phase edit, and only then.** When a request breakpoint proceeds with an **edited** request, re-evaluate the edited URL/method against the Map Local rules; on a match, fetch the body from the desktop and serve it locally instead of hitting the network. The original request is already matched at entry, so an **unchanged** (`proceed(nil)`), aborted, or non-edited request is unaffected; a re-match **miss** falls open to the network exactly as before.
  - **Map Local still wins and short-circuits (v1 preserved).** A request served by the re-match is never handed to the gate — so a rule that also armed the response phase does **not** pause the served response ("Map Local is never broken" still holds, now for the edited request too). New effective order per request: capture filter → Map Local (original) → request breakpoint → **Map Local (edited)** → network → response breakpoint.
  - **Both SDKs, no wire change (invariants #1/#4).** Android `WailoInterceptor` extracts a `serveMapLocal(rule, request, …)` helper used for both the original and the edited request, and re-matches `WailoRuleStore.match(outgoing.url, outgoing.method)` only when `requestEdited`. iOS `WailoURLProtocol.proceedToNetwork(edited:)` re-checks `WailoRuleStore.shared.match(edited.url, edited.method)` and serves via the existing `serveMapped`, else calls the extracted `sendToNetwork(edited:)`. Purely device-side match logic; the engine, desktop, and `protocol` are untouched.
- Alternatives considered:
  - **Keep v1 (an edited request always hits the network):** rejected — this is the ask; editing a URL at a breakpoint should behave like the app issuing that URL, which hits Map Local.
  - **Re-run the *whole* pipeline on the edited URL (re-match breakpoints too, possibly re-pausing):** rejected — a re-pause could loop/recurse and is confusing; v2 re-runs **only** Map Local, once, with no breakpoint re-entry.
  - **Re-match even when the request is unchanged:** rejected — redundant (the original was already matched at entry) and a wasted body-fetch round-trip; gate on `requestEdited`.
- Consequences:
  - An edited request that lands on a Map Local rule is served locally and flagged `edited`; a response-phase breakpoint on that rule does not fire, consistent with "Map Local is never broken."
  - Pre-existing divergence unchanged (ADR-0031): Android's served row shows the request *actually sent* (it rebuilds the real `Request`), while iOS's `serveMapped` emits the *original* request context and response URL — wire-invisible, not worth contorting either path to reconcile.
  - Manual smoke (two-tier verification): with the desktop + a sample app connected, set a **request** breakpoint on URL A (no Map Local rule) and a **Map Local** rule on URL B. Trigger A → it pauses; edit the paused request's URL to B and Resume → the app receives B's mocked status/headers/body, **no** network call is made, and the row is flagged edited. Edit instead to a URL matching **no** rule → it hits the network as before. Add a response phase to B's rule → confirm the served response still does **not** pause. Abort → still fails the app's call.

## ADR-0033: Breakpoints take precedence over Map Local, and a Map Local match *sources* the breakpoint's response (precedence v3, superseding ADR-0027's "Map Local is never broken" and subsuming ADR-0032)

- Status: Accepted; building. No `protocol`/engine/desktop change — device-side interception order only, over the existing wire contract. iOS `WailoBreakpointTests` green (`swift test --filter WailoBreakpointTests` = 7/7: rewrote `testMapLocalTakesPrecedenceOverBreakpoint` → `testBreakpointOwnsExchangeAndMapLocalSuppliesResponse`, added `testBreakpointResponsePhaseShowsMapLocalValue`, renamed the v2 case to `…IsSourcedFromMapLocal`). Android `:sdk-android:testDebugUnitTest` green (added `breakpointRequestPhaseTakesPrecedenceAndMapLocalSourcesResponse` + `breakpointResponsePhaseShowsMapLocalValueAsTheResponse`, renamed the v2 case), run locally outside the sandbox (the SDK build is repo-root Gradle 8.11.1; the agent sandbox can't run the wrapper).
- Context: ADR-0027 (precedence v1) made **Map Local win and short-circuit** — "a Map Local match takes precedence over breakpoints and is never broken; breakpoints apply only on the real-network path" — and ADR-0032 (v2) kept that stance, only re-running Map Local after a request-phase edit. In practice the short-circuit is surprising and limiting: when a URL matches **both** a Map Local rule and a breakpoint, the breakpoint *never fires*, so a mocked exchange can't be paused, inspected, or tweaked — and the failure mode a user actually hit is a breakpoint that "does nothing, even with the exact URL," because a Map Local rule on the same URL was silently shadowing it. The ask inverts the precedence: **breakpoints take precedence over Map Local, but the Map Local value becomes the response shown in the breakpoint.**
- Decision:
  - **A breakpoint owns the exchange.** If a breakpoint rule matches, it runs regardless of Map Local: the request phase can pause/edit/abort before anything is sent, and the response phase can pause/edit/abort before the app sees it. Map Local no longer short-circuits a breakpointed request.
  - **Map Local *sources* the breakpoint's response.** After the request phase resolves, the (possibly edited) outgoing request is matched against Map Local; on a match the desktop body is fetched and *becomes* the response — no network call — and it flows through the **response phase**, so the desktop sees/edits the mocked value exactly as it would a network response. No match → the real network sources it; a Map Local fetch miss falls open to the network. The exchange is flagged `edited` whenever the response came from Map Local (or the request was edited).
  - **Map Local alone is unchanged.** With **no** breakpoint match, Map Local serves and short-circuits exactly as in ADR-0019/0027 (v1). Precedence v3 only changes the *both-match* case. New effective order per request: capture filter → (no breakpoint) Map Local serve-or-network; → (breakpoint) request phase → response source = Map Local(outgoing) else network → response phase.
  - **Subsumes ADR-0032.** Because Map Local is now matched against the outgoing (possibly edited) request as the *response source*, the v2 "re-run Map Local after a request edit" case falls out for free — an edited request that lands on a Map Local rule is sourced from it, now *through* the breakpoint's response phase (if armed) rather than bypassing it.
  - **Both SDKs, no wire change (invariants #1/#4).** Android `WailoInterceptor`: the `breakpoint == null` path keeps `serveMapLocal` (serve + short-circuit + emit); the breakpoint path sources the response via a new `mapLocalResponse(rule, request)` (fetch + build, **no** emit) matched on `outgoing`, then runs the response phase; a `baseEdited = requestEdited || servedFromMapLocal` flag carries the edited state to a resumed-unchanged response. iOS `WailoURLProtocol`: `startLoading` splits on `breakpointMatch`; the breakpoint path calls `sourceResponse(edited:)`, which matches Map Local on the effective request and routes the mapped bytes through `finish` (via `finishMapped`) so the response phase applies uniformly; the old `proceedToNetwork` is gone (replaced by `sourceResponse` + the existing `sendToNetwork`), and a `baseEdited` flag flows through `forwardOriginal`. Purely device-side order; the engine, desktop, and `protocol` are untouched — the desktop already renders/edits whatever response the device pauses on.
- Alternatives considered:
  - **Keep v1/v2 (Map Local wins, breakpoint skipped):** rejected — this is the ask; a breakpoint being silently shadowed by a Map Local rule on the same URL is exactly the confusing behavior being fixed.
  - **Fire the breakpoint but show the *network* response even when Map Local matches:** rejected — the ask is explicit that the Map Local value should be the response in the breakpoint; sourcing from the mock is the point.
  - **Match Map Local against the *original* request only (ignore a request-phase edit):** rejected — matching the outgoing request is more intuitive (editing a paused URL to a mapped one behaves like issuing it) and it subsumes ADR-0032 in one rule.
  - **Re-pause / re-match breakpoints on the edited URL:** rejected as before (ADR-0032) — a re-pause could loop; v3 runs the matched breakpoint once and only re-evaluates *Map Local* for the response source.
- Consequences:
  - A URL matching both a breakpoint and a Map Local rule now pauses at the breakpoint with the Map Local value as its response; resuming delivers that mock (flagged `edited`), editing it delivers the edit, aborting fails the call. Map Local is thus fully inspectable/editable rather than opaque.
  - "Map Local is never broken" (ADR-0027) no longer holds when a breakpoint also matches — the intended inversion. It still holds when no breakpoint matches (the common case).
  - Two control round-trips can now occur for one exchange (a Map Local body fetch **then** a response-phase hold); both already existed independently, and both fail open, so a disconnect at either step proceeds safely.
  - Pre-existing SDK divergence unchanged (ADR-0031): Android mirrors the request *actually sent*; iOS's request context is the edited request it built. Wire-invisible.
  - Manual smoke (two-tier verification): with the desktop + a sample app connected, add a **Map Local** rule and a **breakpoint** on the *same* URL. Trigger it → the breakpoint pauses (it is no longer shadowed); with a response phase, the paused response shows the **Map Local** body → Resume delivers the mock, or edit it → the app receives the edit; no network call is made. Remove the breakpoint → the Map Local rule serves directly as before (short-circuit). Set a request breakpoint on URL A (no Map Local) and a Map Local rule on B → pause A, edit its URL to B, Resume → the response is sourced from B's mock. Abort at either phase → the app's call fails. Quit the desktop mid-hold → the call completes against the real network (fail open).

## ADR-0034: The paused-traffic editor is a dedicated window with an any-order queue, not a modal (supersedes ADR-0027's modal `BreakpointEditor` + one-at-a-time FIFO queue)

- Status: Accepted; building. Pure `studio` presentation change — no `protocol`/engine/SDK/wire change (the `pausedExchanges` seam, the `PausedFlow` bridge, and resume/abort routing from ADR-0027 are untouched). Verified via `ReadLints` + a real cold compile run outside the agent sandbox (`:desktopApp:compileKotlin` and `:shared:compileKotlinJvm --rerun-tasks` both BUILD SUCCESSFUL — the sandboxed wrapper is unreliable per the repo rule); the user smoke-tests via their warm hot-reload terminal. Android/iOS unaffected.
- Context: ADR-0027 shipped the paused editor as a **full-window modal** `BreakpointEditor` — a scrim over the main viewer showing **one** hold at a time, with concurrent hits queued *invisibly* and resolved strictly first-in-first-out. Two problems surfaced in use: (1) the scrim makes the rest of the app inert, but deciding what to do with a held request often means inspecting *other* captured rows — which the modal blocks; and (2) parallel holds are invisible and forced FIFO, so you can't see how many are waiting or triage an urgent one ahead of a boring one. The ask: move the editor into its own window so traffic stays browsable, and show concurrent holds as a visible queue you resolve in any order (no "top first"). This is how the reference tools behave.
- Decision:
  - **A dedicated OS window, not a modal.** The paused editor is a second top-level Compose `Window` (`WailoBreakpointWindowContent` in `shared`, hosted by `desktopApp/Main.kt`) rather than a scrim inside `WailoViewer`. The main viewer stays fully interactive while a hold is open, so captured traffic is browsable *beside* the editor.
  - **Existence derived from the holds.** The window is shown exactly when `engine.pausedExchanges` is non-empty: it opens on the first hit and closes once the last hold resolves — no separate "is the window open" state to keep in sync. The title carries a count when more than one waits (`"Breakpoints (N)"`), mirroring the reference badge.
  - **A visible, any-order queue — always shown.** `BreakpointInspector` renders a left queue panel (each row: phase chip + method/URL + device·app) beside the editor for the selected hold. Resolve in **any** order — select any row, then Resume or Abort it; the rest keep waiting. The panel is shown for **every** count, including a lone hold: hiding it for one and showing it for two made the panel appear/vanish (and the editor jump) as holds arrived and cleared, so it is always present to keep the layout stable. The selected row is marked with a leading **accent bar** (plus a fill) — the fill alone reads too subtly in the monochrome theme. Resolving the selected hold advances selection to the next waiting one.
  - **Selection is the only row action; Resume/Abort live in the editor.** The destructive Abort stays an explicit click in the editor (not a stray tap on a list row). Any-order abort is still one extra click (select the row, Abort), so the requirement is met without a hair-trigger on the list.
  - **Close = fail-open resume-all.** Because the window's presence is *derived* from the holds, its OS close button can only take effect by clearing them. It proceeds every held flow with its **original** bytes — the identical fail-open as a desktop disconnect (ADR-0027) — rather than aborting the app's calls. This preserves ADR-0027's "a hold is never silently stranded / decision-forcing" spirit while giving the close button a safe, coherent meaning (the reference tools' per-item "Cancel/Continue original", applied to all). A no-op close was rejected: with derived visibility the window would just reopen, so an inert X reads as broken.
  - **Full window parity with the main window.** It has a first-run default size and an enforced minimum (via `window.minimumSize`, like the main window's floor), and its floating size/position **persist across close and restart** — a second `WindowStateStore` instance keyed under `breakpointWindow*` (the store was generalized from an object to a `keyPrefix`-parameterized class with `Main`/`Breakpoint` instances; `Main` keeps the original keys so existing geometry survives). Persisted continuously (debounced, only while Floating) plus once on dispose, which covers both close paths (the OS button *and* the last hold being resolved from the editor).
  - **Own theme + shared zoom.** The window wraps `WailoTheme` and the host's text-scale density exactly as `WailoApp` does, and both windows share one Cmd +/- (Cmd 0 reset) key handler, so appearance and zoom match whichever window holds focus (the body editor benefits from zoom).
  - **Session-scoped tab, default Headers.** The Headers/Body tab is hoisted above the per-hold editor and defaults to **Headers**; a switch to Body sticks as later holds arrive within the same window, but it is **not** persisted — reopening the window starts at Headers again. The request `Method` is a dropdown (matching the Map Local / breakpoint-rule editors), not a free-text field, with the held request's own verb folded in so an exotic method stays selectable.
  - **`shared` stays engine-free (ADR-0013).** The window entry lives in `shared` and takes the same `List<PausedFlow>` + resume/abort callbacks the modal took; `Main.kt` wires them to the engine. `WailoApp`/`WailoViewer` shed the three paused params (the modal is gone).
- Alternatives considered:
  - **Keep the modal but add a visible queue inside it:** rejected — the scrim still blocks browsing the traffic you need to make the decision, which is the primary complaint; a window is the ask.
  - **One window per hit:** rejected — no shared surface to triage, and N windows to arrange is noisier than one queue you scan; the ask was explicitly "the queue in the left panel."
  - **Queue in the main window's left, editor in a separate window:** rejected — splits one task across two surfaces and reworks the main layout; a self-contained breakpoint window keeps the work in one place.
  - **Close = abort-all, or a true no-op (can't close):** abort-all is destructive on a stray Cmd-W; a no-op is confusing (and, with derived visibility, just re-opens). Resume-all-unedited is the safe middle.
  - **Preserve strict FIFO one-at-a-time (ADR-0027):** rejected — the ask is any-order triage; FIFO forces resolving an uninteresting hold before an urgent one.
  - **Hide the queue panel for a lone hold (its own earlier v1):** rejected — going 2→1 made the panel disappear and the editor reflow; always showing it trades a little width for a stable, non-flickering layout.
  - **A fresh (non-persisted) breakpoint window each time / a bespoke geometry store:** rejected — the ask is main-window parity; generalizing the existing `WindowStateStore` by key prefix reuses the exact persistence (debounce, Floating-only, NaN sentinels) rather than duplicating it.
- Consequences:
  - ADR-0027's "modal `BreakpointEditor` overlay … **one** paused item at a time; concurrent hits queue behind it" no longer holds; holds are a **visible, any-order** queue in a **dedicated window**. Everything else in ADR-0027 stands (device hold/indefinite-hold/fail-open/abort semantics, the engine seam, the four wire messages).
  - The main window no longer threads `pausedFlows`/`onResumeBreakpoint`/`onAbortBreakpoint` (removed from `WailoApp` and `WailoViewer`); they move to `WailoBreakpointWindowContent`. `BreakpointEditor.kt` now hosts `BreakpointInspector` (queue + editor) and `PausedFlowEditor` (the former modal body, de-scrimmed); the file kept its name.
  - The Cmd +/- text-zoom handler was extracted to a shared `onScaleKeyEvent` and now applies to both windows. `WindowStateStore` went from an `object` to a `keyPrefix`-parameterized class with `Main`/`Breakpoint` instances (main keys unchanged).
  - Manual smoke (two-tier verification): connect the desktop + a sample app; add a request breakpoint and trigger traffic → a separate **Breakpoint** window opens (with the queue panel already showing the one hold) while the main viewer stays scrollable; its `Method` is a dropdown and the editor opens on the **Headers** tab. Trigger several matches at once → the queue lists them with a title count and the selected row shows a leading accent bar; click a **non-top** row and Resume/Abort it → only that hold resolves, the rest remain and the count decrements; the queue panel stays put (no reflow) down to the last hold; resolving the last closes the window. Switch to Body, resolve, and let the next hold open → it opens on Body (session-sticky); close and reopen the window → back to Headers. Resize/move the window, close it, reopen (or restart the app) → it returns to that size/position; drag it below the minimum → it's floored. Edit URL/headers/body and Resume → the app receives the edit; Abort → the app's call fails (`.cancelled`). With holds waiting, click the window's **close** button → all proceed with their original bytes (fail-open) and the window closes. Toggle dark mode in the main window with the breakpoint window open → it matches; Cmd +/- zooms the breakpoint editor. Quit the desktop mid-hold → the app's call completes against the real network (fail-open, unchanged). Smoke the window + queue in both light and dark (ADR-0016).

## ADR-0035: The iOS desktop address is resolved at runtime — Bonjour discovery plus a persisted override reachable from an opt-in on-device panel (closes ADR-0004's deferred mDNS, for iOS)

- Status: Accepted; building. No `protocol`/wire change — discovery is out-of-band and the WebSocket contract is untouched. `swift test` green (30 existing + 7 new `WailoHostStoreTests`); `sample-ios` builds for the Simulator SDK (`xcodegen generate` + `xcodebuild -sdk iphonesimulator`), which is what compiles the UIKit/SwiftUI paths that `swift build` on macOS skips; `cd studio && ./gradlew :engine:compileKotlin :desktopApp:compileKotlin` BUILD SUCCESSFUL. Android is untouched. The amendment below (Simulator carve-out, `present()`, `isGestureEnabled`) was re-verified the same way — `swift build` clean and `sample-ios` **BUILD SUCCEEDED** against the Simulator SDK, which is the only build that compiles the `targetEnvironment(simulator)` branch at all. The address-parsing amendment adds `WailoAddressTests` plus store/`setHost` and end-to-end dial cases (`swift test` 60 green) and was compiled against the Simulator SDK for the same reason.
- Context: `Wailo.start(host:)` defaulted to `localhost:8899`, which is right for the Simulator (it shares the Mac's network stack) and wrong for every physical device, since iOS has no `adb reverse`. The documented answer was to hard-code the Mac's LAN IP in the host app's `init()` — so every DHCP renewal, every network switch, and every new machine meant editing Swift and rebuilding. ADR-0004 chose `adb reverse` as the first transport and explicitly deferred "mDNS/wifi discovery … for devices not adb-connected"; that deferral is exactly what makes physical-device iOS painful. Two distinct problems hide here: *finding* the desktop (discovery) and *overriding* what was found (manual entry, for blocked mDNS or multiple Macs). Both must work without a rebuild.
- Decision:
  - **A four-level resolution order, all runtime-mutable.** Highest first: the `host` passed to `Wailo.start`; then a persisted override in `WailoHostStore`; then Bonjour; then `localhost`. Discovery runs **only** when nothing above it is set, so an explicit address is never silently overridden by some other Mac that happens to be advertising. `WailoCoordinator` recomputes the endpoint on every input change and rebuilds the client **only when the result differs**, so a browser re-reporting the same desktop doesn't redial a healthy connection.
  - **`Wailo.start(host:)` becomes `String?`, defaulting to `nil`.** Source-compatible for callers passing a literal; the behavioural change is that *not* passing a host now means "resolve at runtime" instead of "localhost".
  - **The override lives in `UserDefaults`.** It survives relaunches, and — because `UserDefaults` folds `-WailoHost <ip>` launch arguments into its argument domain — the same key doubles as an Xcode-scheme override for free. Editing scheme arguments relaunches without recompiling.
  - **Desktop advertises, device browses.** `engine` registers `_wailo._tcp` via JmDNS (the JDK has no mDNS) on the address `resolveLanAddress()` returns — the *same* helper that feeds studio's top bar, moved from `desktopApp/Main.kt` into `engine` so the advertised and displayed addresses cannot disagree. Advertising is best-effort: it runs off the caller's thread on `Dispatchers.IO` (JmDNS init blocks ~1s), is wrapped in `runCatching`, and is skipped entirely when there is no LAN route — a failure never stops the WebSocket server. `sdk-ios` browses with `NWBrowser` and resolves each hit to an address through a throwaway `NWConnection`, since Bonjour yields a *service* while `URLSessionWebSocketTask` needs a `ws://host:port` URL and Network.framework exposes no standalone resolve.
  - **The on-device panel is a separate product, `WailoSDKDebug`, linked *instead of* `WailoSDK`.** It is a superset: the same interceptor plus the panel. Release builds link plain `WailoSDK` and cannot reach the debug surface, which keeps UIKit/SwiftUI out of the interceptor that ships inside third-party apps (invariant #3) with no compile-time flag. A superset rather than an additive second product because both must be dynamic (the `+load` dead-strip reason from ADR-0017), and two dynamic products sharing the `WailoSDK` *target* makes Xcode try to build that target dynamically — which it refuses while a product has the same name.
  - **Opened by a two-finger long-press on the bottom half of the screen, with zero host code.** `WailoDebugUIAutoStart`'s `+load` subscribes to scene notifications and attaches a `UILongPressGestureRecognizer` to the *host's* own window with `cancelsTouchesInView = false` and simultaneous recognition allowed, so it observes without ever consuming a touch or blocking the host's recognizers. The panel then lives in a `UIWindow` the SDK owns, because an SDK cannot assume a reachable top view controller. Bottom half only: the top carries the status bar, nav bars, and iPad's multitasking pill.
  - **Amended: the bottom-half filter is lifted in the Simulator, and the panel gained a public `WailoDebugUI.present()`.** The filter made the gesture *unreachable* under a mouse, not merely awkward: Option-click synthesizes two touches mirrored about the *screen's* center, so one of them always lands in the rejected top half, and Option-Shift-dragging the pair down into the bottom half travels further than a long press's `allowableMovement` permits. The two conditions cannot both be met, so `#if targetEnvironment(simulator)` accepts the press anywhere; device behavior is untouched, and the collision argument the filter defends against is worth less on a debug build running on a simulator. Alongside it, `WailoDebugUI.present()` opens the panel over the frontmost scene from any thread and `WailoDebugUI.isGestureEnabled` disarms the built-in trigger, so a host can replace the affordance with a debug-menu row or a button of its own. This is a supplement to the gesture, not a replacement: the default is still zero host code, which is the property the rejected alternatives below all failed.
  - **Amended: the typed address is parsed, not trusted.** Entering an IP *together with* its port — `192.168.1.20:8899`, which is what a field labelled "address" invites — built `ws://192.168.1.20:8899:8899/`, and `URL(string:)` returns nil for that. The transport force-unwrapped it, so a typo crashed the *host app*; and because the panel persisted the text before anything dialled it, `start()` re-read the value on every launch and crashed again — a loop escapable only by deleting the app. `WailoAddress` is now the single gate in front of `UserDefaults` and the transport: it parses free text into a host plus an optional port, accepting `host:port`, a bare IPv6 literal (which it brackets, since an unbracketed one is illegal in a URL authority), and a pasted `ws://…`, and refusing anything Foundation would not parse rather than dialling it and letting it look like "the desktop isn't running". A port found *inside* the address wins over the Port field and is moved into it, so the panel never shows an address other than the one being dialled. `WailoClient` takes a `URL` instead of a host and port, which removes the force-unwrap from shipping code entirely; `Wailo.setHost` reports whether the address was taken and changes nothing when it wasn't, so a working address survives a typo; and the panel prints the reason under the offending field instead of swallowing the tap.
  - **Host apps declare three Info.plist keys.** `NSLocalNetworkUsageDescription` (iOS 14+ gates outgoing LAN connections behind consent, per Apple's TN3179), `NSBonjourServices` (browsing is denied outright without the declared type), and an ATS block — `NSAllowsLocalNetworking` plus RFC 1918 CIDR `NSExceptionDomains` — because the transport is plaintext `ws://` to an IP literal and ATS stopped exempting IP literals in iOS 17. Both sample apps switch from `GENERATE_INFOPLIST_FILE` to an XcodeGen `info:` block, since no `INFOPLIST_KEY_*` build setting can express a dictionary.
- Alternatives considered:
  - **Keep hard-coding the host, just document it better:** rejected — this is the ask; a rebuild per DHCP lease is the problem.
  - **Home Screen quick action to open the panel:** rejected as unbuildable without host code. Registering is free, but with UIScene, `didFinishLaunchingWithOptions` launch options are always nil, so a tap arrives *only* via `connectionOptions.shortcutItem` or `windowScene(_:performActionFor:)` — both scene-delegate methods with no notification equivalent. A library can only intercept by swizzling, and under SwiftUI that means a private internal `AppSceneDelegate`; Firebase's own guidance is to disable swizzling for SwiftUI apps. `addGestureRecognizer` on the host's window is public API by comparison.
  - **App Intents / Shortcuts.app entry point:** rejected — `AppShortcutsProvider` must live in the main app target and cannot go in a framework, and package-vended intents require the app to declare an `AppIntentsPackage`. Host code either way, and the re-export path is widely reported broken.
  - **Shake gesture:** rejected by the user as the trigger; it also collides with host apps that use shake themselves.
  - **Auto-present the panel when the connection fails:** rejected by the user — the panel should appear only when asked.
  - **Always-visible floating pill / edge tab:** rejected — permanent chrome over the host app's UI for something needed rarely. Kept in reserve as a fallback if the gesture proves undiscoverable.
  - **`Settings.bundle` in iOS Settings.app:** the closest thing to a true system shortcut and needs no in-app UI, but SwiftPM namespaces resources into a nested bundle while iOS only reads a `Settings.bundle` at the app root — so it needs a copy build phase in the host. Rejected as host setup.
  - **Ship the panel inside `WailoSDK` (no separate product):** rejected — puts SwiftUI in the interceptor for every consumer and a debug surface one bug away from release. UIKit instead of SwiftUI was also considered and rejected: neither framework is embedded (both are in the shared cache), the deployment floor is already iOS 13 which is exactly SwiftUI's minimum, and UIKit would need `#if canImport(UIKit)` fences since the SDK also builds for the macOS CLI harness.
  - **TXT record carrying the desktop's IP instead of resolving:** rejected — fragile on multi-homed hosts, and it discards the OS's IPv4/IPv6 selection.
- Consequences:
  - ADR-0004's "mDNS/wifi discovery … deferred" no longer holds for iOS. Android is unchanged and still uses `adb reverse`, which already makes the desktop local — it does not browse.
  - `sdk-ios` gains a `Network.framework` dependency in production code (previously test-only) and two new targets. Both are system frameworks; the core `WailoSDK` target stays Foundation-only.
  - `resolveLanAddress()` moved from `desktopApp` to `engine` and is now public API of `engine`. The top bar renders the identical string it did before.
  - `Wailo` gains a small public status surface — `setHost`, `configuredHost`, `activeAddress`, `isConnected`, `discoveredDesktops`, and two `Notification.Name`s. Notifications rather than an `ObservableObject` so the core stays Foundation-only; the Combine binding lives in `WailoDebugUI`.
  - `WailoDebugUI`'s public surface grows from `install()` alone to `install()` + `present()` + `isGestureEnabled` (amendment). No in-repo caller: both samples stay zero-host-code on purpose so they keep demonstrating the default, which leaves the two additions compile-checked but not exercised by a build. `dismiss()` was left out — the panel's Done button already closes it, and a host with no way to ask whether it is open has little use for one.
  - The first connection from a physical device is refused while the Local Network prompt is on screen. No `waitsForConnectivity` was added: the existing 2s reconnect loop already recovers a moment after Allow is tapped.
  - **iOS 18.0–18.5 can report stale Local Network permission state.** Apple tracks this as FB14321888: the persisted Settings toggle and the in-memory policy can become desynchronized, leaving both Bonjour and a direct LAN connection blocked as `localNetworkDenied` even while Settings shows access enabled. Apple fixed it in iOS 18.6; on affected systems the remedy is an OS update or device restart, not a transport retry.
  - Discovery picks the *first* advertised desktop when several are on the network. Deterministic selection is the panel's job, not the resolver's.
  - `Wailo.setHost` became `@discardableResult -> Bool`, and `WailoAddress` is public API — a host app building its own config surface needs the same validation, and the alternative was the panel re-implementing it (amendment). `WailoHostStore` validates on **read** as well as on write, because the argument domain that makes `-WailoHost` work is not ours to sanitize: a launch argument written by hand has to read as "no override" rather than reach the transport.
  - Manual smoke (two-tier verification): with the desktop running and a **physical device** on the same WiFi — launch `sample-ios` → the Local Network prompt appears → tap Allow → within a few seconds the device connects with no address typed anywhere, and the top bar's IP matches. Two-finger long-press the bottom half → the panel opens, the status dot is green, and the Mac is listed under "Found on this network"; confirm a two-finger press on the *top* half does nothing and that ordinary taps/scrolls still work while the gesture is armed. Type a wrong IP → Connect → the dot goes grey; tap "Use discovery instead" → it reconnects. Force-quit and relaunch → a pinned address is still in force (persistence). Move the Mac to a different network so its IP changes → with no override set, the device re-finds it. Run the Simulator with the desktop stopped and started → `localhost` still works with no prompt. Pass `-WailoHost <ip>` in the scheme, relaunch without recompiling → that address is used. Quit the desktop → the device reconnects on its own when it comes back. In the **Simulator** (amendment): hold Option and press anywhere for a second → the panel opens, which no mouse input could achieve before the filter was lifted; confirm ordinary clicks and scrolls still work while the gesture is armed.
  - Manual smoke, typed addresses (amendment): type `192.168.1.20:8899` into **Desktop IP** and Connect → the port moves into the Port field, the IP field keeps only the IP, and it connects — this input used to crash the app on the spot. Type `1.2.3.4:80:90`, a port of `70000`, or `hello world` → Connect is refused with a red field and a reason below it, the address already in force keeps serving, and a force-quit + relaunch comes back on that address instead of crashing at launch (the persisted-crash loop). Paste `ws://192.168.1.20:8899/` → accepted as the same address. Clear the field → discovery resumes. Read the error line in both light and dark (ADR-0016).

## ADR-0036: The capture server's port is changeable at runtime from a studio Settings panel — the engine rebinds in place rather than being rebuilt

- Status: Accepted; building. `studio`-only — no `protocol`/wire change and no SDK change (a device still just dials a `host:port`). `cd studio && ./gradlew :engine:test :desktopApp:compileKotlin` BUILD SUCCESSFUL with a new `WailoEnginePortTest` (4 tests) green alongside the existing engine suites; `ReadLints` clean. Android/iOS untouched.
- Context: ADR-0035 made the *device* side of the address runtime-mutable, but the desktop side of the same knob stayed compile-time: `WailoEngine.port` was a constructor `val` and `Main.kt` built exactly one engine in a `remember`, so 8899 was effectively hard-coded. That bites when something else already owns 8899, when two Wailo instances need to coexist, or when a corporate policy reserves the range — and the fix was editing Kotlin and rebuilding, the same complaint ADR-0035 fixed for iOS. There was also no Settings surface at all to put such a control on.
- Decision:
  - **Rebind in place; never rebuild the engine.** `port` becomes a `var` with a private setter and a new `rebind(newPort)` stops the server, moves the port, and re-listens. Everything worth keeping — the captured exchanges, the Map Local / capture-filter / breakpoint snapshots, their epochs, the `bodyProvider` wiring — hangs off the instance, so constructing a new `WailoEngine` on the new port would silently discard a session's work. A port change costs the sockets, nothing else.
  - **Trial-bind before binding.** Ktor CIO binds on a background coroutine, so a failed bind does not reliably propagate out of `start(wait = false)` — it would leave `server` set with nothing listening, which is the worst possible state (a UI that looks healthy over a dead server). `listen()` first probes with a throwaway `ServerSocket`, with `reuseAddress = true` so a port our *own* previous bind left in `TIME_WAIT` still reads as free while one another process is actively listening on reads as taken; `runCatching` covers the residual probe-to-bind race.
  - **A refused port rolls back.** `rebind` restores the previous port and re-listens on failure, so the app is never left with nothing bound. It suspends and does the work on `Dispatchers.IO`, because tearing the old server down blocks for up to `STOP_TIMEOUT_MS` and Apply is a click on the UI thread.
  - **Two questions, two answers.** `start()`/`rebind()` return "did the requested port take effect"; a separate `listening` property answers "is anything bound at all". One Boolean can't express both, and the difference matters exactly when it's confusing — "your new port was refused, the old one is still serving" reads very differently from "nothing is listening".
  - **The host validates, because only the host can.** `shared` must not depend on `engine` (ADR-0013), so the panel hard-codes no port range and guesses at no policy: it filters to five digits, hands the number up, and renders whatever verdict comes back through `portError`. Range-checking client-side would be a second source of truth for `PORT_RANGE` *and* still wouldn't answer the real question, since a perfectly valid port can be occupied.
  - **A failed bind is visible without opening Settings.** The top bar's dot goes red and the address reads `… — not listening`. Otherwise the one failure a user cannot diagnose — no traffic ever arrives, and the address looks fine — stays invisible behind a panel they have no reason to open.
  - **The docked tool slot becomes one value.** Settings is the fourth panel, and four `*Open` booleans with hand-written mutual exclusion is twelve assignments and a reachable "two panels docked" state. `WailoViewer` now holds a single `ToolPanel?`, so the illegal state is unrepresentable and the rail is a list of equals.
  - **Settings sits at the foot of the rail.** Bottom is where a settings entry is looked for, and it separates "configure the app" from the three traffic tools. The rail keeps its one-click light/dark flip — checking a screen in both themes is an ADR-0016 habit, not something worth opening a panel for.
  - **The panel ships with one section.** Appearance (a home for the theme and for the text scale, which is still Cmd +/- with no visible control) was built here and then pulled back out at the user's request, to be landed as its own change rather than riding along with a transport fix. A settings panel is a container; the cost of adding the second group later is the group itself. The lone "Connection" header stays for that reason — it's the shape the next section drops into, not decoration.
  - **Persistence follows the existing seam.** A new `PortStore` (`listenPort`) built on `createKeyValueStore("desktop")`, exactly like `ThemeStore`/`TextScaleStore`. It re-defaults when the stored value is out of range, so a hand-edited preference can't leave the app unable to bind at launch.
- Alternatives considered:
  - **Recreate `WailoEngine` on the new port (keyed `remember`):** rejected — the obvious implementation, and it throws away every captured exchange and rule snapshot on a port change. The state is the product.
  - **Persist the port and apply on next launch:** rejected by the user — a restart to test a port change is barely better than a rebuild, and it wouldn't match the SDK panel, where `setHost` reconnects immediately.
  - **Bind and see what happens (no probe):** rejected — CIO's asynchronous bind means the exception surfaces on a background coroutine, if at all. The failure mode is a green UI over a server that never came up.
  - **Mirror `PORT_RANGE` into `shared` for client-side validation:** rejected — duplicates a constant across the module firewall to answer a question that isn't the interesting one. Occupancy, not range, is what makes a port unusable, and only the host can test it.
  - **Settings as a modal, or its own window like the breakpoint editor (ADR-0034):** rejected — the breakpoint window exists because you must browse traffic *while* deciding. Settings is configuration; the docked tool slot is already the home for panels you open, use, and close.
  - **Keep the Appearance section that was already working:** rejected by the user — it is a second, unrelated concern in a change about the transport, and it drags an open question with it (whether the theme should become tri-state System / Light / Dark; `ThemeStore` persists a Boolean today, so once you toggle there's no way back to "follow the OS"). Settling that inside a port change would have been the tail wagging the dog. Deferred whole.
- Consequences:
  - `WailoEngine.port` is no longer a constructor `val` and `start()` now returns `Boolean`; `rebind`, `listening`, and `PORT_RANGE` are new public API. `server` became `@Volatile` now that `listening` reads it from the caller's thread.
  - `WailoViewer`'s `mapLocalOpen`/`captureOpen`/`breakpointsOpen` collapsed into one `ToolPanel?`; `ToolRail` takes `openPanel` + `onSelectPanel` instead of three pairs. `WailoApp` gains `listenPort`, `listening`, `portError`, and `onApplyPort`; `listenAddress` in `Main.kt` is no longer a one-shot `remember`.
  - New in `shared`: `SettingsManager` plus an `ic_settings` mono drawable.
  - Changing the port drops every connected device. iOS devices that *discover* self-heal (the engine re-advertises `_wailo._tcp` on the new port, ADR-0035); an iOS device with a pinned `host:port` and every Android device do not — Android needs `adb reverse tcp:<new> tcp:<new>` re-run. The panel says so rather than leaving it to be discovered.
  - Bonjour registration is now keyed to a `bindGeneration` counter instead of a `server != null` check. The old guard existed to catch "stopped while `JmDNS.create` was blocking (~1s)", which was sufficient while `start()` ran exactly once; a rebind is a stop *and* a start, so by the time a straddling registration resumes there is a new server in the field and the stale one would adopt it — two Applies inside a second would leave a second advertiser publishing a dead port with nothing tracking or closing it. The generation check plus a small lock around the check-and-handover closes both the wide window and the narrow one against a concurrent `stop()`. Not unit-tested: reproducing it means interleaving a real one-second JmDNS init, and the test would be a flake generator.
  - **Bonjour discovery was broken the whole time, for an unrelated reason found while verifying this.** The advertiser named the service instance `InetAddress.getLocalHost().hostName`, which on macOS is an FQDN (`<hostname>.local`). A DNS-SD instance name is a single label, and Apple's mDNSResponder silently ignores a registration whose name carries a dotted `.local` suffix — so iOS, which browses through that same stack, never saw the desktop, and ADR-0035's discovery path could not have worked. Nothing threw: `registerService` returned successfully, and JmDNS is lax enough that a second JmDNS instance discovers and fully resolves the malformed name, so every Kotlin-side check looked healthy. Isolated by registering both spellings from one JmDNS instance on one interface at the same instant and browsing with `dns-sd`: the dotted name never appeared, the stripped one appeared immediately. Fixed by taking the first label. The lesson generalizes — a peer JmDNS is not a valid oracle for "will Apple's stack see this", because the two disagree on strictness.
  - **Known gap, not addressed here:** macOS Sequoia+ gates Bonjour operations behind Local Network consent, but exempts processes launched from a terminal *and their children* — which is exactly `:desktopApp:run` and `:desktopApp:hotRun`, so the dev loop never sees it. A packaged `.app` launched from the Dock is not exempt, and `nativeDistributions` currently sets no `infoPlist` (no `NSLocalNetworkUsageDescription`, no `NSBonjourServices`) and no signing identity — which TN3179 also requires for the grant to persist. The first DMG will therefore be silently undiscoverable to iOS while still accepting devices on a typed address, since *listening* is never gated. `registerBonjourService`'s `runCatching` makes any such failure invisible by design. To be resolved when studio is actually packaged.
  - Manual smoke (two-tier verification): open Settings from the foot of the tool rail → the port field shows the live port and the address below it matches the top bar. Change it to a free port and Apply (or press Enter) → the top bar's address updates, previously-captured rows are **still listed**, and a device redialling the new port connects. Re-run `adb reverse` on the new port → the Android sample reconnects; leave an iOS device on discovery → it re-finds the desktop unaided. Apply a port something else is using → an inline error appears, the top bar keeps the old address, and traffic on the old port still arrives. Apply `0` or `70000` → refused with a range message. Restart the app → it comes back on the chosen port. Occupy that port with another process *before* launching → the top bar dot is red and reads "not listening", and Settings shows why; free the port, hit Apply on the same number → it recovers. Check that opening Settings closes whichever other panel was docked. Smoke the panel in both light and dark (ADR-0016). Verify the advertisement with Apple's own resolver, not a Kotlin client — `dns-sd -B _wailo._tcp local.` must list the host, and must re-list it on the new port after an Apply; that one command is what surfaced the instance-name bug above.

## ADR-0037: macOS Studio connects to physical iOS apps through the built-in usbmuxd, with USB preferred and LAN as fallback

- Status: Accepted; building. No protocol change and no native dependency. `swift test` is green (46 tests, including nine USB-listener/takeover/port cases); `cd studio && ./gradlew :engine:test :desktopApp:test :shared:jvmTest` is green, including transport-neutral engine and plist/usbmux framing tests. Windows/Linux USB support is deferred.
- Context: ADR-0035 made physical iOS usable over Bonjour, but that path still depends on Wi-Fi, ATS, Local Network consent, and an iOS 18.0–18.5 permission-state bug outside the SDK's control. Android's `adb reverse` avoids all of those. `iproxy`/usbmuxd cannot reverse-forward a device client's `localhost` to a Mac server: their direction is Mac → device. A real USB alternative therefore needs an app-local listener and reverses who opens the WebSocket, while preserving which side sends each protocol message.
- Decision:
  - **Studio speaks Apple's built-in usbmuxd directly.** `desktopApp` implements the small plist-v1 protocol over `/var/run/usbmuxd`: one long-lived Listen socket reports every USB device and one Connect socket per device becomes a raw tunnel. A loopback-only JVM bridge lets the JDK WebSocket client use that tunnel. No `libusbmuxd`, `libplist`, `iproxy`, JNI, Homebrew install, or extra DMG payload is required.
  - **The iOS SDK listens on port 8900 whenever `Wailo.start()` is active.** It uses `NWListener` + `NWProtocolWebSocket`, accepts one Studio connection, sends the existing `Hello` first, and carries one existing `Envelope` per binary frame. Port 8900 is deliberately distinct from LAN 8899; using 8899 would let the fallback `localhost` client connect to its own listener. Network.framework cannot bind `NWListener` to loopback explicitly, so the listener rejects every accepted peer whose remote endpoint is not loopback.
  - **Both ends can move that port, and must be moved together.** 8900 is a default, not a constant: the SDK persists an override in `WailoHostStore` (so `-WailoUsbPort` also works as a scheme argument, like the LAN host) and Studio persists its own in `UsbPortStore`. There is no negotiation because usbmux forwards to a number and advertises nothing — so the panel and Settings both name the port they are using, and a mismatch is only visible as **Waiting for an app**. A rebuild to dodge a port collision would defeat ADR-0035 for the transport that is supposed to be the reliable one.
  - **Both sides re-arm themselves.** iOS tears down an app's network resources while it is suspended, which kills the listener permanently — the observed symptom was USB never returning after the phone idled, even after a re-plug, until the app was restarted. The listener now re-binds when it fails after having been ready and when the app returns to the foreground. Studio, symmetrically, polls `ListDevices` alongside the event stream, because a Listen socket can stay open and silent through a sleep/wake and leave Studio blind until the cable is pulled.
  - **USB wins automatically.** An accepted USB link suspends (but does not discard) the outbound LAN client and becomes the sole capture/body-fetch/breakpoint transport. On unplug or tunnel failure it fails pending work open, restores the same manual-host/Bonjour/localhost LAN configuration, and reconnects. There is never a dual-stream fan-out.
  - **Session semantics are transport-neutral in the engine.** `DeviceConnection` preserves binary message boundaries and identifies its transport; the LAN Ktor server and USB connector both enter one handler for snapshot push/ack retry, capture recording, Map Local, and breakpoint routing. `connectedDevices` exposes Hello-identified sessions without making the UI talk to transport code.
  - **All attached devices are attempted.** usbmuxd already emits attach/detach events, so limiting Studio to the first iPhone would add an arbitrary product restriction without reducing protocol complexity. A new **Devices** panel shows LAN sessions and each USB device as connecting, waiting for an app, connected, or failed.
  - **The LAN and USB ports are independent.** Settings configures the desktop LAN server and, separately, the device port to dial over USB. A LAN rebind neither changes nor drops an established USB path; changing the USB port re-dials only USB.
- Alternatives considered:
  - **Bundle/run `iproxy`:** rejected — it adds an external executable, packaging/licensing work, and process supervision while using the same daemon protocol Studio can implement in a small testable JVM layer.
  - **PeerTalk or another SDK dependency:** rejected — the SDK ships inside third-party apps and Network.framework already provides the listener/WebSocket; a dependency would buy no wire capability.
  - **Raw length-prefixed protobuf over USB:** rejected — keeping WebSocket message framing means USB and LAN use the exact same transport contract and engine session handler.
  - **Manual USB/LAN mode or first-device-only:** rejected by the user in favor of automatic USB-first fallback and all attached devices.
  - **Keep device-as-client by treating usbmux as `adb reverse`:** rejected as technically false; usbmux Connect reaches a port on the device, not a service on the Mac.
- Consequences:
  - Both `WailoSDK` and `WailoSDKDebug` include the listener, as requested. It starts only while Wailo is active and accepts only loopback peers; consumers must still keep Wailo out of production builds if capture itself is debug-only.
  - The app must be running and unsuspended for the listener to accept. An attached phone with no active Wailo app appears as **Waiting for an app**, and Studio retries without blocking LAN or the UI. A port set differently on the two sides looks identical to that, which is why both surfaces print the number they are dialling.
  - usbmuxd device handles are ephemeral; Studio keys display/reconnect state by UDID and replaces the handle after a reattach. Network-synced devices reported by usbmuxd are ignored — only `ConnectionType=USB` enters this path.
  - The usbmux protocol is reverse-engineered rather than a public Apple API. Its packet framing, XML plist messages, network-byte-order port, attach/detach behavior, and raw-tunnel transition are covered by fake-daemon tests, but physical-device smoke remains mandatory on supported macOS/iOS versions.
  - ADR-0010's USB deferral is closed for macOS only. Windows/Linux remain deferred rather than receiving an untested `usbmuxd` packaging story.
  - Manual smoke (two-tier verification): build/link the updated iOS SDK, run Studio on macOS, connect a trusted physical iPhone by USB, and open an app using Wailo → Devices changes **Attached/Connecting → Connected**, the SDK reports `usb:8900`, and traffic plus Map Local/capture-filter/breakpoint control work with Wi-Fi off and Local Network denied. Connect a second iPhone → both appear and stream independently. Unplug one during normal capture and during a body fetch/breakpoint hold → only that USB session disappears, pending work fails open, and its existing LAN path reconnects when Wi-Fi is available. Leave the app closed → the phone remains **Waiting for an app** and connects after launch. Change Studio's LAN port in Settings → the USB session remains connected. Check Devices in light and dark. Repeat from a packaged `.app` to prove no terminal-only executable or Homebrew dependency was accidentally assumed.
  - Manual smoke, port and recovery: change the port in the on-device panel *only* → Devices falls to **Waiting for an app** on the old number; set Studio's USB device port to match → it reconnects, and both survive an app relaunch and a Studio restart. Lock the phone and leave it until USB debugging drops, then unlock → the session returns **without a re-plug and without relaunching the app**; repeat with the cable pulled and reinserted while the app stays open.

## ADR-0038: The on-device panel is themed from the shared design tokens, and the iOS SDK stays native Swift

- Status: Accepted; implemented. `xcodebuild -scheme WailoSDKDebug -sdk iphonesimulator` builds clean. Appearance is manual-verification only (both schemes, on device) — nothing here is machine-checkable.
- Context: The panel from ADR-0035 was a stock SwiftUI `Form` with system colors (`Color.green`, `.secondary`, the grouped-list background). It therefore followed iOS's palette rather than Wailo's in both schemes, and its three same-weight sections gave no reading order — the thing a user opens the panel to learn (is anything connected, and over what) had no more prominence than a text field. Making the USB port configurable added a fourth thing to place. The Android SDK will eventually want the same panel, which raised whether to write it once in Compose Multiplatform.
- Decision:
  - **Share the tokens, not the UI code.** `~/Documents/personal/projects/ai-assists/design-tokens/tokens.json` stays the single source; `WailoTokens.swift` is generated from its semantic layer with the same role names the studio's Compose theme uses, and carries the same DO-NOT-EDIT header. Visual consistency comes from the data, not from a shared widget tree.
  - **No Compose Multiplatform in `sdk-ios`.** It would link a Kotlin/Native runtime and Skia into every host app — invariant #3 and ADR-0010 directly. Confining it to `WailoSDKDebug` does not help much: that is still a runtime shipped into someone else's process for a settings sheet. The Android panel will use Compose natively against the same tokens.
  - **Hand-built layout instead of `Form`.** UIKit's grouped list paints itself from the system palette and cannot be brought onto a custom one per row. Sections are ordered by the question being asked: Status, then Found on this network, then Manual address, then USB.
  - **Type follows the token scale on the system face.** Sizes and weights come from `tokens.json`; the family does not, because bundling Noto Sans into a library means registering a font at runtime inside someone else's app.
- Alternatives considered:
  - **A Compose Multiplatform panel shared with the future Android SDK:** rejected as above. Revisiting it means an ADR that reopens ADR-0010, not a UI decision.
  - **Restyle `Form` via `UITableView.appearance()`:** rejected — a global UIKit appearance mutation from inside an embedded SDK is exactly the kind of side effect on the host app this SDK must never have.
  - **Bundle Noto Sans in the debug product:** deferred. `CTFontManager` registration plus a resource bundle is real weight for a panel nobody reads for long, and it would be the only resource the package ships.
- Consequences:
  - `WailoDebugUI` gains the generated `WailoTokens.swift`; no system color remains in the panel, and the palette is the only place a color is chosen.
  - Tokens are now instantiated twice (Compose and Swift), so a `tokens.json` change needs regenerating in both. Drift shows up as the two surfaces disagreeing; Style Dictionary is the fix if it ever bites.
  - Manual smoke (two-tier verification): open the panel in light *and* dark → chrome, cards, fields, and buttons read as Wailo rather than iOS, the status dot is green when connected / amber when started but not connected / grey before start, the transport chip says USB while the cable is in, and the USB card shows the live listener port. Raise the system text size → the panel scales without clipping. Pin an address, then clear it, and confirm the status line's wording follows.

## ADR-0039: Wi-Fi sessions are mutually authenticated and encrypted; loopback and USB are not

- Status: Accepted; implemented on iOS and Studio. `sdk-ios` `swift test` and `cd studio && ./gradlew :engine:test` are green, including the cross-platform crypto vectors. Android is not done — `sdk-android` still speaks the pre-handshake protocol and can only reach Studio over `adb reverse` (loopback) until it is.
- Context: ADR-0035 gave iOS Bonjour discovery, and the SDK dialled whatever `_wailo._tcp` advertisement it found first. On a shared network — an office, a co-working space, a conference — that is a colleague's Studio as readily as your own, and the loser is not the person who misconfigured something: **the device sends `Hello` (device name, bundle id) and then streams full request and response bodies, including auth headers, to a machine nobody chose.** Studio's side is worse, because the moment a socket opened it pushed the rule set, the capture filter, and the breakpoint rules — every intercepted host and every Map Local path — to the peer before it had said a word. Both directions leaked to anyone who could answer an mDNS query.
- Decision:
  - **Wi-Fi proves both ends; loopback and USB prove neither, deliberately.** The Simulator, `adb reverse`, and the usbmux tunnel all terminate on 127.0.0.1, where the kernel already guarantees the peer is this machine. A key there would protect against nothing and would be one more thing to go wrong in the path people use most. So `isTrusted` short-circuits the whole handshake, and the failure the ADR exists to stop cannot occur on those transports by construction.
  - **Studio signs (ECDSA P-256); the device answers with an HMAC.** The two sides get different mechanisms because their exposure differs. Studio's identity must survive a device being lost or a device-side key store being read, so only its private key can produce a valid challenge. A device only has to convince the one Studio it paired with, and a symmetric proof is enough for that — which keeps the device side small, as invariant #3 requires.
  - **P-256, not Ed25519, purely for reach.** `java.security` exposes Ed25519 only at Android API 33; the SDK floor is 24. `SHA256withECDSA` has been in the platform since API 11 and `P256.Signing` since iOS 13. ECDSA's real hazard — a reused or biased signing nonce leaking the private key — cannot reach a device, because only Studio ever signs.
  - **`studio_id` is the fingerprint of the public key, and rides in the Bonjour TXT record.** This is what makes discovery self-authenticating: an impostor can advertise someone else's `sid`, but it would need a key that hashes to it to answer the challenge. The device dials, fails to verify, and hangs up, rather than racing the real Studio for attention.
  - **Two ways to pair, because a camera is not always usable.** A QR carries a full-entropy secret and the public key out of band. A typed code carries ~50 bits, so it is stretched with 200k rounds of PBKDF2 and expires after two minutes — otherwise anyone who recorded one pairing could brute-force it offline and decrypt every session that device ever has.
  - **Studio also MACs its challenge, not just signs it.** During a first pairing over a typed code the device has no trusted copy of the public key, so a signature alone proves nothing — an impostor would sign with its own key and present the matching fingerprint. The mac is keyed by the pairing secret, which only the Studio displaying the code knows.
  - **Everything after the handshake is AES-256-GCM, per direction, per session.** The session key is derived fresh from both nonces, which is what lets the frame counter restart at zero each connection instead of being persisted across the SDK's 2-second reconnect loop — a counter that survives reconnects is how GCM nonce reuse happens in practice.
  - **Keys live in the OS key store on both sides.** macOS Keychain for Studio, iOS Keychain for the device. Explicitly *not* `java.util.prefs`, which on macOS is a plaintext plist: anything that could read that file could impersonate this Studio to every paired device and decrypt their traffic.
  - **A device's session counter is a clone tripwire.** It advances every handshake; a counter that goes backwards means the same key authenticated from somewhere else. Nothing here can stop that — both ends hold the same secret — but a silent compromise is worse than a visible one, so it is surfaced and not acted on.
  - **The `auto_start` path is gated on the app being debuggable.** `WailoBootstrap` checks `get-task-allow` in the embedded provisioning profile (`SecTask` is macOS-only), so an SDK that slipped into a release build does not quietly begin advertising for a desktop.
- Alternatives considered:
  - **Filter discovery to a manually entered allow-list of hosts, and leave the wire alone:** rejected — it addresses "which desktop do I dial" and none of "who is actually answering". An attacker who can answer at the right address wins, and nothing is encrypted afterwards either.
  - **TLS with a self-signed cert:** rejected — pinning a self-signed cert is the same trust problem in a heavier wrapper, and it drags certificate lifetime, `ATS` exemptions, and a second config surface into an SDK that must stay dependency-light.
  - **Asymmetric device identity too:** rejected for now. It buys a device key that cannot be extracted and replayed, and costs Android API-24 reach (see P-256 above) plus a device-side key store on every platform. The session counter covers the case it would have prevented well enough to be worth revisiting rather than blocking on.
  - **A PAKE (SPAKE2/OPAQUE) instead of stretching the typed code:** deferred — it would let the code shrink and remove the offline-guess concern outright. It is the proper fix if the ten characters ever grate; 200k PBKDF2 rounds is the cheap version of the same property.
- Consequences:
  - `protocol` gains `AuthRequest`/`AuthChallenge`/`AuthResponse`/`AuthResult`/`SealedFrame` as oneof fields 13–17. The field is `sealed_frame`, not `sealed`: Wire escapes `sealed` to `sealed_` on the Kotlin side only, which would leave the two generated APIs spelling one field differently.
  - The crypto is implemented **twice** — `WailoCrypto.swift` against CryptoKit, `WailoCrypto.kt` against `java.security` — because the SDK build and the studio build are separate Gradle builds joined only by `protocol` (ADR-0015). Nothing keeps them interoperable except the shared vectors asserted in both test suites. Drift shows up in the field as devices that pair and then silently fail to authenticate, with nothing in either log to say why; the vectors exist to make that a red test instead.
  - Keys cross the wire as X9.63 uncompressed points and signatures as raw `r || s`, not the JVM's native SPKI/DER, because those are the forms CryptoKit exposes at **iOS 13** — `derRepresentation` needs 14. Studio converts on its own side, including a hand-rolled DER↔raw signature codec, since the studio build has no BouncyCastle and this is the entire ASN.1 surface either side needs.
  - Forgetting a device, and resetting Studio's identity, both hang up on live sessions rather than waiting for the next reconnect. "Forget" has to mean something while you are looking at a device that is currently streaming your traffic.
  - The iOS debug panel gains a QR scanner (`AVFoundation`), which means a host app that wants it must declare `NSCameraUsageDescription` — checked before requesting access, because asking without it terminates the app. Absent, the panel says so and points at the typed code.
  - Manual smoke (two-tier verification): with Studio and a physical iPhone on the same Wi-Fi, connect before pairing → nothing streams and Studio shows a refusal notice rather than traffic. Pair by QR → traffic flows; confirm in Studio's Devices panel that the device is listed. Kill and relaunch the app → it reconnects without pairing again. Pair a second device by typed code, and let a code expire without using it → it is refused. Forget the device in Studio *while it is streaming* → the session drops immediately. Reset Studio's identity → every device drops at once and none reconnects until re-paired. Run the Simulator and a USB device throughout → both keep working with no pairing at any point.

## ADR-0040: Wi-Fi is trust-on-first-use by default; pairing is the opt-in strict mode

- Status: Accepted; implemented on iOS and Studio, superseding ADR-0039's mandatory-pairing default. Cross-platform vectors green on both sides. Android still pending, as in ADR-0039.
- Context: ADR-0039 shipped with pairing required for every Wi-Fi device. Living with it made the cost obvious: the overwhelmingly common case is one developer, one phone, one Mac, on a home or office network, connecting to an address that developer typed themselves — and that case now needs a QR ceremony every time the key store is cleared. Meanwhile the actual leak ADR-0039 was written for was never really "an unpaired device connected". It was **automatic** connection: Bonjour picking a stranger's Studio with nobody deciding anything. Those are separable, and conflating them made everyone pay a ceremony to fix a problem only discovery had.
- Decision:
  - **Discovery may only reconnect to something already trusted; anything new is a deliberate act.** This, not the ceremony, is the fix. Bonjour's job narrows to finding a desktop this device already holds a key for, wherever DHCP has moved it. Reaching a new desktop always requires the user to type its address or scan its QR.
  - **A first contact at an address the user named is taken at its word, and pinned (TOFU).** The device keeps the identity that answered and refuses anything else at that address afterwards. This is the SSH model, and its bargain is the same: an attacker has to already be in the path at that one moment, and from the next connection on, being in the path is too late. In exchange, the everyday case has no ceremony at all.
  - **Tapping a discovered desktop fills the address field; it does not connect.** A row in a list is a suggestion. Since a first contact pins whatever answers, that must never happen from a stray tap — the user still has to press Connect. (Requested by the user, along with giving Connect a visible result, since before this the button looked identical before and after a tap.)
  - **Strict mode is a switch in Studio's Settings, off by default.** On, Studio refuses any device it has not been introduced to by QR or code. Devices already trusted stay trusted when it is switched on: it gates the first contact, which is the only moment it could have made a difference, and dropping known devices would just be a surprise. Persisted, because a security setting that silently relaxes on the next launch is worse than never having offered it.
  - **An identity change at a remembered address stops everything and asks.** Your friend's Mac takes the IP yours had; the device refuses, and shows the pinned fingerprint against the one now answering so the user can compare them with what Studio prints in its own Settings. Accepting is explicit and drops the old pin. Never resolved automatically — a machine that changed hands and someone standing in the path are indistinguishable from the device's side, and that is exactly the decision pinning exists to force.
  - **Every Wi-Fi handshake now carries an ephemeral P-256 key agreement, and the session key comes from the agreed secret concatenated with the long-term key.** A first contact has no shared secret, so agreement is the only route to one — but mixing it in *everywhere* costs nothing extra on the wire and buys forward secrecy: a long-term key that leaks later cannot open traffic captured earlier, which is the property that makes revoking a key worth anything. It also collapses TOFU and paired into one derivation instead of two ways to be wrong. The long-term key a first contact leaves behind is itself derived from that connection's agreed secret by both ends independently, so it is never transmitted.
  - **The challenge signature covers both ephemeral keys.** Without that they are the one part of the handshake nobody vouches for, and anyone in the path could substitute their own into a replayed challenge and hold a separate session with each side — a man in the middle wearing the real Studio's signature.
  - **Studio's identity reset moved from the Devices panel into Settings**, behind a confirmation that names how many devices it will disconnect. It is a rare, destructive act about this Studio, not about any one device (requested by the user).
- Alternatives considered:
  - **Send the long-term key in the clear on the first connection and encrypt everything after:** rejected — far simpler, and it hands every future session to anyone who merely recorded the first one. Agreement is not much more code and removes the passive attacker entirely.
  - **Keep pairing mandatory and just make it faster:** rejected by the user. No amount of polish makes a ceremony worth it for someone connecting their own phone to their own Mac, and it would leave the real problem — automatic discovery — untouched.
  - **Let strict mode retroactively distrust leniently-trusted devices:** rejected by the user. Turning on a switch and having working devices silently stop is a worse failure than the one it prevents; the devices are listed and can be forgotten individually.
  - **Pin trust to the address rather than the identity:** rejected — an identity survives DHCP moving the Mac, an address does not. The address only enters as the key for detecting a *changed* identity, which is the one place it is the right question.
  - **Persist "the user accepted this identity change" across launches:** rejected — accepting is a decision about right now, in front of a fingerprint the user just compared. A stale acceptance surviving a relaunch would silently widen it.
- Consequences:
  - `AuthRequest`/`AuthChallenge` gain `ephemeral_key`. `AuthRequest.studio_id` may now be empty, which is how a first contact says "I have not been told who is listening here". The auth and session labels bumped to `v2` since their derivation changed; the vectors in both suites were regenerated together.
  - `WailoCrypto.decodePublicKey` now checks the point is actually on P-256. The JCA will build a key from a point that is not, and that key then goes into ECDH — the setup for an invalid-curve attack, where the answers leak the private scalar. CryptoKit checks this on its side; the Kotlin side now matches rather than trusting the provider.
  - `WailoHandshake` is organised around a `Trust` input (`firstContact` / `invited` / `paired`) instead of a nullable key and a nullable pinned key, so "what may this connection demand" is one value rather than a set of correlated optionals.
  - Both sides now record *how* a device came to be trusted and show it, because "paired" and "trusted on first contact" carry genuinely different guarantees and a user tightening things up needs to tell them apart.
  - Stored pairings from before this change no longer decode (the record gained a field, and Swift's synthesized `Decodable` does not apply defaults for absent keys). They are dropped rather than crashing, and the device re-establishes on first contact — correct here, since the protocol change had already invalidated them.
  - `sdk-android` has no handshake yet, so it is now *refused* over Wi-Fi rather than merely unpaired: admission rejects any non-loopback connection whose first frame is not an `AuthRequest`. Its documented path (`adb reverse`, loopback) is unaffected, but an Android host app pointed at a LAN address stops working until the Kotlin device half lands. Neither test suite catches this — Studio's tests drive loopback and the wire tests drive iOS — so it is called out in the README instead.
  - Manual smoke (two-tier verification): with a clean install, open the panel → the discovered desktop is listed. **Tap it → the address field fills and nothing connects.** Press Connect → it connects, and Studio's Devices panel lists it as *Trusted on first contact*. Force-quit and relaunch → it reconnects unaided. Turn off the address override and let Bonjour find it → still connects. Now start a **second** Studio on the same network that this device has never met → discovery must not connect to it, and no `Hello` reaches it. Turn on **Only paired devices over Wi-Fi** in Studio's Settings → the already-trusted device stays connected; clear the device's pairings and reconnect → it is refused with a message naming the switch. Pair by QR → accepted again, now listed as *Paired*. For the identity change: connect to a Mac, then stop Studio there and start one with a different identity at the same address (reset the identity in Settings is the easy way) → the device refuses, shows both fingerprints, and *stays* refused until Accept is pressed; press Keep the old one → it stops dialling that address. Reset identity from Studio's Settings → the confirmation names the device count, and every device drops at once. Smoke Settings and the panel in both light and dark (ADR-0016).

## ADR-0041: Seeds are desktop-side canned responses that a breakpoint hold spends, in order

- Status: Accepted; implemented in `shared` + `desktopApp`. `cd studio && ./gradlew :shared:jvmTest` is green. No `protocol` or SDK change.
- Context: A breakpoint (ADR-0027) stops an exchange and waits for a human. That is what you want the first time; it is exactly what you don't want when the thing you're testing is a *sequence* — poll returns 202 then 202 then 200, a retry succeeds on the third attempt, a paginated list runs out. Hand-editing the held response each time is slow, error-prone, and unrepeatable, and Map Local can't help: it answers by rule, so the same URL gets the same answer forever. The user asked for "seed": a Map-Local-shaped response, minus the name, prepared ahead of time and applied to holds in order.
- Decision:
  - **A seed is spent when it is used.** This single rule is what turns a list into a script: two seeds for the same URL answer two successive requests differently, and running out falls back to the human. It is why the armed queue is a queue and not a rule set, and it is the whole difference between Seed and Map Local.
  - **Response-phase only, and that is the wire's constraint, not a simplification.** A breakpoint decision carries `edited_response`, and the SDKs honour it only for a RESPONSE-phase hit (`capture.proto`); a request-phase hold has no response to substitute. So a request-phase hold always needs a human, and the window opens for it.
  - **Seeds never reach a device.** Unlike Map Local — which pushes match metadata and serves bodies on demand (ADR-0019) — a seed is matched and resolved entirely on the desktop, because a hold is already a desktop round-trip. Nothing to push, nothing to compile, no `protocol` change.
  - **Which means the desktop now matches URLs itself, for the first time.** `seedUrlMatches`/`seedMethodMatches` deliberately reimplement the device's scheme byte for byte (`sdk-android/WailoMatching.kt`: split on `*`, escape the literals, whole-string, case-sensitive URL, case-insensitive method, blank method = any). A seed is routinely copied from a Map Local rule, so the two matching differently would be a trap; `SeedMatchTest` pins it.
  - **Arming is explicit and the queue is session-scoped.** Filling is a button in the breakpoint window, not automatic: a queue that armed itself would spend seeds on traffic the user didn't mean to script. It lives in the host, so it survives closing and reopening the window — but not a restart, because a half-spent queue is a snapshot of a run in progress, not a preference. Fill flattens groups away and keeps only the layout order, so "don't bring the group, keep the order".
  - **Matching happens on a hold's arrival, once.** A hold already sitting in the editor is in the user's hands; filling the queue must not reach in and answer it behind their back.
  - **The breakpoint window becomes user-owned, amending ADR-0034.** It used to exist exactly while something was held. That can't survive seeds: a seed-answered hold must not flash a window open and shut, and arming a queue requires the window *before* the traffic arrives. So it opens from the Breakpoints panel or from a hold that needs attention, and closes only when the user closes it. Resolving the last hold leaves it open on the seed list, ready for the next run.
  - **Seeds reuse the whole grouped-rule stack** (`LayoutRule`/`LayoutNode`/`GroupedRuleListPage`, ADR-0026/0028) and the response editor, which was extracted out of Map Local into `ResponseRuleEditor` for this. Groups organize the panel and gate whole sets out of a fill; they don't survive into the queue.
- Alternatives considered:
  - **Answer holds from Map Local's existing rules:** rejected — Map Local is stateless by design (same URL, same answer). Making its rules spendable would break the feature it already is, and there is no place in it to say "then this one".
  - **Auto-fill the queue from the enabled seeds whenever the window opens:** rejected — it makes the queue's contents an implicit consequence of opening a window, so a partly-spent run silently resets and unrelated traffic gets scripted. Fill is one click and says what it did.
  - **Persist the armed queue across restarts:** rejected by the user. The queue is a position in a run; restoring it after a relaunch would resume a script whose first half never happened.
  - **Re-run matching whenever the queue changes, so filling answers what's already waiting:** rejected — see "on arrival, once" above.
  - **Keep the window's lifetime derived from the holds and add a separate "seed staging" surface:** rejected — two places to look at one queue, and the window still couldn't be opened to arm it.
  - **Give seeds a name field like Map Local's:** rejected by the user. A seed is identified by what it matches and where it sits in the order; a name would be a second, driftable identity for the same thing.
- Consequences:
  - The Seed panel is a fourth tool-rail entry, and Map Local rows gain a right-click **Seed…** that copies the rule (match, status, headers, body file) into it. The import lands in the host, not `shared`, because it spans both layouts and copies a file.
  - `MapLocalHeader` became `ResponseHeader` and moved to `ResponseRule.kt` with `PickedFile`, since two features now author responses. `MapLocalStore`'s private `appDataDir`/`extensionForContentType`/`guessContentType` and the served-header assembly moved to `AppData.kt` for the same reason — one copy, so Map Local and Seed can't drift on what they serve.
  - Map Local's ~550-line `RuleEditor` moved wholesale into `ui/ResponseRuleEditor.kt`, parameterized by title and a nullable initial name (null ⇒ no Name field). `MapLocalManager` keeps only its Navigation 3 shell.
  - The breakpoint window's left column is now two lists — Waiting over Seed — with a draggable split, and the column itself is draggable. Both sizes are `remember` state, **not** persisted: this window is opened for a task and closed, so its proportions are a per-session convenience, unlike the main window's tool panel. `PanelResizeHandle`/`DragHandle` moved from `WailoViewer` to `Common.kt` to be shared.
  - A seed whose body file has gone missing does **not** answer and is **not** spent; the hold falls through to the user. Resolving a hold with an empty body would be a silent, wrong success.
  - The feature master (ADR-0030) comes free: off, the list dims, Fill collects nothing, and triage skips matching, with the saved layout untouched.
  - Manual smoke (two-tier verification): right-click a Map Local rule → **Seed…** → it appears in the Seed panel with the same match and body. Open the breakpoint window from the Breakpoints panel **with nothing paused** → it opens and stays open. Fill, then trigger two requests to one URL with two seeds for it → each gets its own response, neither appears in Waiting, the window never flashes, and both leave the Seed list but stay in the Seed panel. Trigger an unmatched URL → the window comes forward with it in Waiting. Resolve it → the window stays open. Trigger a **request**-phase breakpoint with a matching seed armed → it still waits for you, and the seed is not spent. Drag the left column and the Waiting/Seed divider to their floors, restart → the sizes reset. Scroll both lists with enough items to show a scrollbar. Check the window in light and dark (ADR-0016).

## ADR-0042: A breakpoint rule list has no order to drag

- Status: Accepted; implemented in `shared`. Amends ADR-0028.
- Context: ADR-0028 generalized Map Local's grouped, drag-orderable list so Breakpoints could reuse it, and Seed joined on the same stack (ADR-0041). But the three features consume their lists differently. Map Local and Seed resolve by **first match** — top-to-bottom order picks the winner, and for Seed it is also the sequence a run is scripted in. Breakpoints has no winner: every enabled rule whose pattern matches pauses the exchange, so the list is a set the order is drawn from, not a priority. Dragging a breakpoint rule therefore changed nothing observable, which is worse than no control at all — it invites the user to tune something that isn't there.
- Decision: `GroupedRuleListPage` gains `reorderable` (default true) that drops the drag handles from both the rule rows and the group headers; Breakpoints passes false. Everything else about the panel is unchanged: groups still organize rules and gate them off together, rules still add/edit/delete/toggle, and the saved layout is still an ordered list — nothing about the persisted format or the codec changes, so turning this back on is one argument.
- Alternatives considered:
  - **Keep the handles and let the order be meaningless:** rejected — that's the state this ADR is correcting.
  - **Give breakpoint order a meaning (e.g. first match wins, later rules ignored):** rejected — a URL can legitimately want a request-phase rule and a response-phase rule at once, and "only one breakpoint rule may fire" would silently drop the second.
  - **Drop groups from Breakpoints too:** rejected — grouping still earns its place there; toggling a whole set of breakpoints off in one switch is the common move (ADR-0026), and that has nothing to do with order.
- Consequences:
  - `handleModifier` on the shared row/header composables is nullable now, and null is what hides the grip; a row without one keeps the same leading gap, so a grouped rule's switch still lands under its group header's.
  - Breakpoint rows sit 24.dp further left than Map Local's and Seed's. That difference is the point — the panels no longer look identical because they no longer behave identically.
  - Manual smoke: open Breakpoints → no grips on rules or group headers, and nothing drags; add a group, drag-free reorder is impossible, but collapse/rename/delete and the group's switch all still work. Map Local and Seed still reorder.

## ADR-0043: One pane, shared by holds and seeds — and an arriving hold takes it only when the user isn't already in one

- Status: Accepted; implemented in `shared` + `desktopApp`. Extends ADR-0041.
- Context: ADR-0041 gave the breakpoint window a left column of two lists — holds Waiting over the armed Seed queue — but only holds were selectable; seeds were display-only rows, so the one thing you could not check before a seed was spent was *what it would actually answer with*. Meanwhile the window had become user-owned, which introduced a question its old lifetime never had to answer: a hold can now arrive while the window is open but buried, or open and in front with the user mid-edit on a different hold. "Always jump to the newest" and "never move" are both wrong — the first throws away work, the second hides a device that is blocked and waiting.
- Decision:
  - **The right pane shows a selection, not a hold.** Selecting a seed previews it there: its match, the status and headers it answers with, and its stored body through the same previewer the traffic list uses, so an image or binary seed reads as itself. The preview is read-only with no actions — a seed is authored in the Seed panel and spent by traffic, not by anything in this window.
  - **A hold arriving raises the window only when the window is not focused.** In front already, it is not raised; there is nothing to raise it above.
  - **It takes the pane unless the pane already holds another paused exchange.** Buried window ⇒ raise and select it, because the user hasn't seen it. In front and showing a seed (or nothing) ⇒ select it, since nothing is lost. In front and showing a hold ⇒ leave it alone: that hold is mid-edit, and its edits are unsaved state that switching away destroys.
  - **Opening an already-open window brings it forward.** The panel's open button used to set a flag that was already true, so once the window was buried the button did nothing. The host keeps a raise counter instead, which both the button and the inspector's arrival rule bump.
  - **Selection is resolved against what still exists, every composition.** A resolved hold and a spent seed both vanish under the user; the pane falls back to the first hold still waiting, then to the empty state. That is one rule covering both list kinds instead of two effects racing to fix up a stale id.
- Alternatives considered:
  - **Always select the newest hold:** rejected — the common case for concurrent holds is editing one carefully while others queue, and this discards that edit on every arrival.
  - **Never move the selection; badge the new hold instead:** rejected — a buried window can't show a badge, and the device is blocked in the meantime.
  - **Raise the window on every arrival:** rejected — it steals focus from whatever the user is doing for a hold they may already be looking at.
  - **A separate seed-detail window or an expanding row:** rejected — the pane is already the window's "what's selected" surface, and two ways to look at one queue is the thing ADR-0041 avoided.
  - **Let the host decide the arrival policy:** rejected — the policy depends on what the pane is showing, which is the inspector's own state; splitting the decision across the boundary means duplicating that state.
- Consequences:
  - The inspector reads `LocalWindowInfo.isWindowFocused` inside the arrival effect rather than in composition, so focus changes don't recompose the window.
  - `WailoBreakpointWindowContent` takes `onLoadSeedBody` and `onBringToFront`; the host answers the first from `SeedStore` and the second by bumping the counter its window raises on.
  - Both lists share one row scaffold, so a seed and a hold read as the same kind of pick (accent bar + fill). Seed rows are two lines now — URL over `METHOD → status` — because one line left no room for the URL in a column that drags narrow.
  - Manual smoke: with the window open, click a seed → its headers and body show on the right, no buttons; spend it and the pane falls back. Bury the window behind the main one and trigger a hold → it comes forward with that hold selected. With the window in front and a hold being edited, trigger a second hold → the editor doesn't move. Repeat while a *seed* is selected → the new hold takes the pane. Bury the window and press the Breakpoints panel's open button → it comes forward.

## ADR-0044: Fill answers the holds already waiting

- Status: Accepted; implemented in `desktopApp`. Amends ADR-0041's "matching happens on a hold's arrival, once".
- Context: ADR-0041 evaluated seeds only when a hold arrived, reasoning that a hold already sitting in the editor is in the user's hands and the queue must not reach in and answer it behind their back. In use that reasoning turns out to cover the wrong case. The ordinary way to reach for seeds is *reactive*: traffic pauses, you look at what's waiting, and only then do you arm the queue for it. Under arrival-only matching, Fill at that moment does nothing visible — the hold you filled *for* keeps waiting, and the only way out is to release it and re-trigger the traffic, which for a retry or a poll sequence means restarting the scenario. The queue is right there, matching, and declines to help.
- Decision:
  - **Fill arms the queue and then sweeps the waiting holds**, answering each response-phase hold that a seed matches and spending that seed, in queue order — so two waiting holds for one URL take seeds 1 and 2 exactly as two successive requests would.
  - **"On arrival, once" still governs the automatic path.** Nothing else re-runs matching: a seed being spent, a rule being edited in the Seed panel, or a hold resolving all leave the other holds alone. The distinction that makes both rules coherent is *who asked* — automatic matching stays conservative because the user didn't ask for it at that moment, while Fill is a named button the user pressed while looking at the queue it will spend.
  - **The sweep does not spare the hold open in the editor.** Consistency beats caution here: a Fill that skipped whatever happened to be selected would answer a different set of holds depending on where the user last clicked, which is impossible to predict or explain. Any in-progress edits to that hold are lost, which is the same thing that happens to them on Resume.
  - **Both paths spend through one function** (`spendSeedOn`), so triage and Fill can't drift on what a seed is allowed to answer — the request-phase exclusion and the missing-body-file fallback are stated once.
- Alternatives considered:
  - **Re-match on every queue change:** rejected again, and for the original reason — it makes answering a hold an implicit side effect of unrelated edits.
  - **A separate "Apply to waiting" button next to Fill:** rejected — two buttons for what is one intention, and the second would be the one you always want after the first.
  - **Sweep, but skip the selected hold:** rejected — see above; a control whose effect depends on the current selection isn't predictable.
  - **Ask before answering the holds:** rejected — a confirmation on every Fill to protect a case the user just asked for.
- Consequences:
  - Fill is no longer instantaneous: it reads a body file per answered hold, so it runs in a coroutine and re-reads the queue between holds rather than folding a local copy — otherwise a hold arriving mid-sweep could be triaged against a queue the sweep has already spent from, and the sweep's final write would resurrect the spent seed.
  - A Fill with nothing waiting behaves exactly as before, which is still the common case when arming ahead of the traffic.
  - Manual smoke: trigger a response-phase hold and leave it waiting, *then* Fill with a matching seed armed → the hold resolves and the seed leaves the queue, with no further traffic needed. Do it with two holds on one URL and two matching seeds → each takes its own, in order. Do it with a request-phase hold waiting → it keeps waiting and no seed is spent. Fill with nothing waiting → the queue just arms.

## ADR-0045: A seed is authored from the traffic it will stand in for

- Status: Accepted; implemented in `shared`. Extends ADR-0041.
- Context: A captured row could already become a Map Local rule or a breakpoint rule from its right-click menu, but a seed could only be authored by hand or imported from an existing Map Local rule. That left the most natural way to build one unreachable: you watch a request come back, decide you want that exact answer replayed into the next hold, and there is no way to say so from the row you are looking at. Retyping the URL, method, status, headers, and body by hand is both tedious and the easy way to author a seed that silently never matches.
- Decision:
  - **"Seed…" joins "Map Local…" and "Breakpoints…" on a captured row**, seeded with the same URL and method and opening the Seed panel on its editor — the identical seam, not a new one.
  - **It opens the editor rather than committing the seed**, unlike the Map Local panel's own "Seed…", which copies a finished rule and so has nothing to ask. A captured exchange is raw observation, and the reason to seed from one is usually to change something about it, so the editor is where the action lands.
  - **It carries the observed status code; Map Local does not.** A seed replays an exchange, so an observed 500 or 429 is often the entire point of capturing it; a mapping authors a new response, for which 200 is the better start. A row with no response falls back to 200.
  - **Headers and body come across through the same filter Map Local uses** (`seededHeaders` plus the raw response bytes), so the two row actions can't disagree about what "the captured response" means.
  - **A row-carried body is retired once saved.** The captured bytes only ever open the editor; after Save the stored body is authoritative, so reopening that seed in the still-open panel shows what was saved rather than replaying what the row carried.
- Alternatives considered:
  - **Commit the seed straight from the row, like the Map Local import:** rejected — it would append a seed that answers with the exact bytes just observed, which is rarely what is wanted and gives no moment to change the status or trim the body.
  - **Give Map Local the observed status too, for symmetry:** rejected — symmetry is not the goal; the two rules mean different things, and a mapping that silently starts at the captured 404 would be a worse default than 200.
  - **One "Seed…" that reuses the Map Local draft:** rejected — the drafts differ (a seed has no name, and now a different status default), and sharing one would make the panel that opens depend on hidden state.
- Consequences:
  - `SeedManager` gains `initialDraft`/`initialBodySeed` and a per-id body-seed map, matching `MapLocalManager`; `ResponseRuleEditor` already took a `bodySeed`, so nothing in the editor changed.
  - `WailoViewer` holds the seed draft alongside the other two, and clears it whenever the panel is reached any other way (the rail, or a Map Local import) so a stale draft can't hijack the panel.
  - Manual smoke: right-click a captured row with a JSON response → Seed panel opens on an editor pre-filled with that URL, method, status, headers, and body; Save → it appears in the seed list and fills into the breakpoint window. Do it on a row that returned 500 → the editor shows 500. Do it on an image response → the body tab previews the image. Edit the body, Save, reopen the seed from the list → the edited body is what shows. Right-click a row with no response → the editor opens at 200.
