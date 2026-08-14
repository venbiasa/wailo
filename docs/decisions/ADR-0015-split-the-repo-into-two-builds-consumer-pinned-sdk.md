---
adr: 0015
title: Split the repo into two builds — consumer-pinned SDK + a modern `studio` desktop build
date: "2026-07-12"
status: accepted
date_source: git-commit
---
# ADR-0015 — Split the repo into two builds — consumer-pinned SDK + a modern `studio` desktop build

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
