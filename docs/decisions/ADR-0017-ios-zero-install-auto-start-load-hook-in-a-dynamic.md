---
adr: 0017
title: iOS zero-install auto-start — `+load` hook in a dynamic SDK (implements ADR-0009's iOS half)
date: "2026-07-15"
status: accepted
date_source: git-commit
---
# ADR-0017 — iOS zero-install auto-start — `+load` hook in a dynamic SDK (implements ADR-0009's iOS half)

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
