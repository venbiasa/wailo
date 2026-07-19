package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The Compose-native editor (ADR-0023) is the default. Flip to true to fall back to the legacy Swing /
// RSyntaxTextArea editor, kept compiled during the transition so a regression is one line to revert; a
// follow-up deletes [SwingCodeEditor] + the rsyntaxtextarea dependency and collapses this expect/actual.
private const val USE_SWING_FALLBACK = false

@Composable
internal actual fun CodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
) {
    if (USE_SWING_FALLBACK) {
        SwingCodeEditor(state, language, readOnly, modifier)
    } else {
        ComposeCodeEditor(state, language, readOnly, modifier)
    }
}
