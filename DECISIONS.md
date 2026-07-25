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
