---
adr: 0022
title: Map Local panel navigates via Navigation 3; Add is a FAB; the nav layer is multiplatform-ready
date: "2026-07-18"
status: accepted
date_source: git-commit
---
# ADR-0022 — Map Local panel navigates via Navigation 3; Add is a FAB; the nav layer is multiplatform-ready

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
