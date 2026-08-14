---
adr: 0026
title: Map Local rules gain single-level groups; list order is match priority; drag-and-drop is hand-rolled
date: "2026-07-22"
status: accepted
date_source: git-commit
---
# ADR-0026 — Map Local rules gain single-level groups; list order is match priority; drag-and-drop is hand-rolled

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
