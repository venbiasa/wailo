---
adr: 0013
title: The M3 desktop viewer (overview) — where it lives, its seam, and its look
date: "2026-07-11"
status: accepted
date_source: git-commit
---
# ADR-0013 — The M3 desktop viewer (overview) — where it lives, its seam, and its look

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
