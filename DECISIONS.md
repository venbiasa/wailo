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
  Waylay needs an isolated SDK, an engine, and samples.
- Decision: Adopt the wizard's toolchain and conventions (version catalog with plugin aliases,
  `com.android.kotlin.multiplatform.library` DSL, foojay, `shared` + `desktopApp` naming) and add
  `protocol`, `core`, `sdk-android`, `engine`, `sample-android` as sibling modules.
- Consequences: Known-good toolchain matrix; standard layout; the SDK stays decoupled from the UI.

## ADR-0006: iOS core-sharing decision deferred

- Context: iOS interceptor must be native; only `core` could be shared, via Kotlin/Native.
- Decision: Build Android first with `core` in KMP; keep `core`'s surface small and port-based; decide
  native-Swift vs Kotlin/Native-framework for iOS when iOS work starts.
- Consequences: No premature commitment; protobuf already carries the contract cross-language.
