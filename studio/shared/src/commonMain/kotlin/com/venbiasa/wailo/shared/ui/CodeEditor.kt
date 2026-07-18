package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The language a [CodeEditor] highlights. Deliberately small — grown as new surfaces need it. */
internal enum class CodeLanguage { Json, PlainText }

/**
 * The document handle for a [CodeEditor], kept apart from the widget so the platform actual owns the
 * live text (a large document must not ride in Compose snapshot state, or every keystroke would
 * recompose and re-copy megabytes). Callers read [currentText] on demand (save/format/validate) and
 * replace the whole document with [setText] (format/seed). [revision] ticks on each user edit —
 * observe it *off* the composition (e.g. `snapshotFlow`, debounced) so live validation never couples
 * a keystroke to a recomposition.
 */
@Stable
internal class CodeEditorState(initialText: String = "") {
    // The text the editor should show on (re)mount; also the fallback before the widget is attached.
    internal var seedText: String = initialText
        private set

    // Wired by the actual to read/replace the live document. Null until the widget is composed.
    internal var reader: (() -> String)? = null
    internal var writer: ((String) -> Unit)? = null

    var revision by mutableStateOf(0)
        internal set

    fun currentText(): String = reader?.invoke() ?: seedText

    /** Replaces the whole document (Format / seed-from-capture). No-op-safe before the widget mounts. */
    fun setText(text: String) {
        seedText = text
        writer?.invoke(text)
    }

    /**
     * Nudges [revision] so observers re-read after a programmatic change (which deliberately doesn't
     * bump it, since a Format/seed isn't a user edit) — e.g. to re-run validation on the new document.
     */
    fun touch() {
        revision++
    }

    /**
     * Called by the widget's actual when it leaves composition (e.g. the editor's tab is hidden): snapshot
     * the live text into [seedText] and drop the widget wiring, so a later remount reseeds from the user's
     * edits — not the stale initial text — and [currentText] stays correct while detached (Save can happen
     * from another tab).
     */
    internal fun detach() {
        reader?.let { seedText = it() }
        reader = null
        writer = null
    }
}

@Composable
internal fun rememberCodeEditorState(initialText: String = ""): CodeEditorState =
    remember { CodeEditorState(initialText) }

/**
 * A code text area for [state]'s document, highlighted for [language] and editable unless [readOnly].
 * Portable seam (no platform types in the signature): the desktop actual embeds a real code editor
 * for large-document performance, while a future mobile actual can back the same contract without
 * touching call sites (ADR-0020). Colors are read from the theme by the actual, so it tracks
 * light/dark.
 */
@Composable
internal expect fun CodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
)
