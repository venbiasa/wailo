---
adr: 0024
title: Sunset the Swing / RSyntaxTextArea code editor
date: "2026-07-21"
status: accepted
date_source: git-commit
---
# ADR-0024 — Sunset the Swing / RSyntaxTextArea code editor

- Status: Accepted. Completes the follow-up ADR-0023 deferred: with the Compose-native editor proven as the
  default, the flag-gated Swing/RSTA fallback and its dependency are removed.
- Context: ADR-0023 shipped the Compose-native `CodeEditor` as the default (`USE_SWING_FALLBACK = false`) but
  kept `SwingCodeEditor` compiled behind a JVM `actual`, plus `libs.rsyntaxtextarea` in the catalog, so a
  regression was one line to revert. That safety net has outlived its purpose and was the last thing keeping a
  heavyweight AWT/Swing component — and a desktop-only dependency — in the studio build.
- Decision:
  - Delete `SwingCodeEditor` and the `CodeEditor` JVM `actual`; collapse the `expect/actual` seam to a plain
    `commonMain` composable (`CodeEditor`, renamed from `ComposeCodeEditor`). `CodeEditorState` drops
    `replaceAllFromWidget`, which only the widget-backed fallback used. The two `shared/commonMain` files are
    renamed to match: the model is `CodeEditorState.kt`, the composable `CodeEditor.kt`.
  - Remove `com.fifesoft:rsyntaxtextarea` from the `studio` catalog and `shared`'s `jvmMain` (it was the only
    `jvmMain` dependency, so the block goes with it).
  - Remove `compose.interop.blending=true` from the desktop `main()`. ADR-0021 set it so Compose popups (the
    method dropdown, context menus) could composite over the editor's `SwingPanel`; with no `SwingPanel` left
    anywhere in the app it is dead code. ADR-0023 already scoped this removal to this follow-up.
- Consequences:
  - `shared` is free of Swing/AWT interop; the editor is a single Compose composable in `commonMain`, one step
    closer to running Map Local on non-JVM targets (ADR-0022's gap (a)).
  - The one-line RSTA revert is gone: a future regression in the hand-rolled editor is fixed forward, not by
    flipping back to Swing (the risk ADR-0023 bounded is now accepted outright).
  - Manual smoke is unchanged from ADR-0023 (type/select/copy/paste/undo/find; large-body scroll incl. first
    open; JSON highlighting and current-line/selection in both light and dark; Cmd +/- scaling) — plus, since
    interop blending is off, confirm the method dropdown and context menus still render above the editor.
