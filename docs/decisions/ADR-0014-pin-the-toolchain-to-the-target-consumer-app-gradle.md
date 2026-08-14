---
adr: 0014
title: Pin the toolchain to the target consumer app (Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.2.21)
date: "2026-07-12"
status: accepted
date_source: git-commit
---
# ADR-0014 — Pin the toolchain to the target consumer app (Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.2.21)

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
