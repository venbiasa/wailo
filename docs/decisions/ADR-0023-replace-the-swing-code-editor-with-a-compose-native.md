---
adr: 0023
title: Replace the Swing code editor with a Compose-native, viewport-virtualized editor
date: "2026-07-19"
status: accepted
date_source: git-commit
---
# ADR-0023 — Replace the Swing code editor with a Compose-native, viewport-virtualized editor

- Status: Accepted; building. **Reverses ADR-0020's editor technology** (`RSyntaxTextArea` via `SwingPanel`)
  while keeping ADR-0020's *architecture* (the portable `CodeEditor` expect/actual seam, the `CodeEditorState`
  holder, inline app-managed bodies, and hand-rolled JSON format/validate). ADR-0021's interop-blending
  rationale is now vestigial for this editor (see Consequences).
- Context: ADR-0020 chose RSTA precisely because Compose's `BasicTextField` lays the *entire* document out as
  one `MultiParagraph` on every edit — O(n) per keystroke — so it stalls past a few hundred KB. RSTA solved
  the performance requirement but cost us: (1) it is the app's **only** heavyweight AWT/Swing component, so
  the **first** open of the Mapping Rule editor pays a large one-time cold-start (Compose↔Swing interop
  bootstrap — worsened by `compose.interop.blending=true` — plus RSTA class-loading and the AWT font
  manager), all synchronous on the UI/EDT thread, which janks the list→editor transition; and (2) it is
  desktop/JVM-only, so it is the single biggest blocker to ever running Map Local on mobile/web (ADR-0022's
  "not multiplatform yet" gap (a)).
- Key insight: Compose *can* do this — the O(n) cost is in the *layout of one giant `MultiParagraph`*, not in
  the buffer. If we **virtualize by line** (only lay out visible lines, like the read-only `BodyPreview`
  tree/hex already do with `LazyColumn`) and keep the text in a **line-model buffer** (not one immutable
  `String`), then per-keystroke cost is bounded to *one line + the viewport*, independent of document size.
  Pretty-printing on load is load-bearing here: it converts a minified one-liner into many short lines, which
  is exactly what line-virtualization needs (a single 10 MB line would defeat it).
- Decision:
  - **A Compose-native `CodeEditor`, in `commonMain`.** A `LazyColumn` of highlighted lines over an
    `EditorBuffer` (an `ArrayList<String>` of lines; edits splice the affected line span, O(1) amortized for
    typing). `CodeEditorState` is rebuilt around that buffer + caret/selection/undo as Compose state, but
    **keeps its existing contract** (`currentText`/`setText`/`touch`/`revision`) so `RuleEditor` and the
    debounced-validation effect are unchanged. The big document lives in the buffer, never in a single
    snapshot `String`, so a keystroke never re-copies megabytes (same principle ADR-0020 stated, now met
    without Swing).
  - **Custom caret/selection/input model as the single source of truth.** Full cross-line selection +
    copy/cut/paste, caret navigation (arrows/home/end/page, shift to extend), undo/redo (coalesced typing),
    auto-indent on Enter, auto-close brackets/quotes, a line-number gutter, current-line highlight,
    horizontal scroll for long lines, and Cmd/Ctrl+F find. Monospace makes hit-testing/caret math a constant
    `charWidth`. JSON highlighting is a **per-line** tokenizer (`jsonHighlightSpans`) — safe because a JSON
    string literal can't span lines — so highlighting cost is viewport-bounded and size-independent; it is
    reused verbatim for the active line and the static lines (one visual language).
  - **Text input is via key events (`utf16CodePoint`), desktop-first.** Correct for authoring/pasting JSON on
    desktop; on-screen-keyboard **IME is a documented follow-up** behind the same seam (the component's API is
    already portable; only the input source is desktop-oriented). This is the one honest gap versus a fully
    mobile-ready editor, called out so it isn't mistaken for done.
  - **Size thresholds (tunable constants).** Auto-format (pretty-print) on load/seed/save up to ~10 MB
    (skip + notice beyond — the format pass is a cheap O(n) pass run off the UI thread, and it is what keeps
    big payloads renderable); live JSON validation up to ~2 MB (debounced/off-thread, else validate on Save);
    per-line tokenizing skipped for a pathological single line (> ~10k chars); smooth-editing target ~5 MB.
  - **RSTA kept temporarily behind a flag.** `libs.rsyntaxtextarea` stays in the `studio` catalog and the
    Swing editor stays compiled (self-contained, `SwingCodeEditor`) but **unwired**; the JVM `actual`
    dispatches to the Compose editor by default via a `const val`, so a regression is one-line-revertible.
    A follow-up deletes the Swing editor + the dependency once the Compose editor is proven, at which point
    the `CodeEditor` expect/actual can collapse to a plain `commonMain` composable.
- Alternatives considered:
  - **Keep RSTA, just prewarm it off the critical path** (background class-load + a throwaway `SwingPanel` at
    startup): fixes the first-open jank cheaply but keeps the desktop-only blocker and the AWT component in
    the main window. A good fallback; rejected as the primary path because it does nothing for portability.
  - **One `BasicTextField`/`TextFieldState` over the whole doc**: still lays out the whole `MultiParagraph`
    per edit → the exact O(n) stall ADR-0020 fled. Rejected.
  - **A windowed single `BasicTextField`** (edit only the visible slice, swap on scroll): keeps native
    within-window selection/IME but the slice-swap and absolute-offset mapping are janky and complex, and
    selection can't exceed the window. Rejected for the line-model, which is simpler to reason about.
  - **Per-line `BasicTextField` for every line**: gets IME per line but makes cross-line selection (the
    explicit requirement) very hard (Compose selection doesn't span sibling fields). Rejected.
- Consequences:
  - The editor moves to `commonMain`; `EditorBuffer` and `jsonHighlightSpans` are pure and unit-tested
    (the "testable so it won't break later" tier). `BodyTab`'s call site is unchanged (still `CodeEditor`).
  - ADR-0021's `compose.interop.blending=true` was set so the method dropdown could composite over the Swing
    panel. With no Swing panel in the editor, that flag is **no longer required for correctness** here; it is
    left in place for now (harmless) and its removal is folded into the RSTA-deletion follow-up.
  - New manual smoke checks (non-machine-verifiable): type/select/copy/paste/undo/find in the body editor;
    scroll a large (multi-MB) pretty-printed body and confirm smoothness incl. first open; JSON highlighting
    and current-line/selection read correctly in **both** light and dark; Cmd +/- still scales the editor.
  - Risk acknowledged: a hand-rolled editor is less battle-tested than RSTA (edge cases: bidi/RTL, complex
    IME, extremely long single lines). The flag-gated RSTA fallback and the ~5 MB target bound that risk.
