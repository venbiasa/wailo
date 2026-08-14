---
adr: 0020
title: Map Local response-body editor — inline app-managed bodies + a Swing code editor behind a portable seam
date: "2026-07-18"
status: accepted
date_source: git-commit
---
# ADR-0020 — Map Local response-body editor — inline app-managed bodies + a Swing code editor behind a portable seam

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
