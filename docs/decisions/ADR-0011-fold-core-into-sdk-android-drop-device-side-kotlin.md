---
adr: 0011
title: Fold `core` into `sdk-android`; drop device-side Kotlin Multiplatform (amends invariant #4)
date: "2026-07-08"
status: accepted
relations: amends invariant #4
date_source: git-commit
---
# ADR-0011 — Fold `core` into `sdk-android`; drop device-side Kotlin Multiplatform (amends invariant #4)

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
