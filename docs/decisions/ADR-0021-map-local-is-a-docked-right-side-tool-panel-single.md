---
adr: 0021
title: Map Local is a docked right-side tool panel, single-method, seeded exactly from a row
date: "2026-07-18"
status: accepted
date_source: git-commit
---
# ADR-0021 — Map Local is a docked right-side tool panel, single-method, seeded exactly from a row

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
