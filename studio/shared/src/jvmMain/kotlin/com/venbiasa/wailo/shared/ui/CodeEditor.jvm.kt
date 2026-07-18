package com.venbiasa.wailo.shared.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.venbiasa.wailo.shared.theme.LocalWailoColors
import com.venbiasa.wailo.shared.theme.WailoColors
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.roundToInt
import java.awt.Color as AwtColor

// Folding parses the whole document up front, which is the one part of RSTA that scales with size
// rather than viewport; above this it's turned off so large payloads stay responsive (highlighting
// itself is tokenized lazily per visible line, so it stays on). ADR-0020.
private const val FOLDING_MAX_CHARS = 1_000_000

/**
 * Desktop [CodeEditor]: an RSyntaxTextArea embedded via [SwingPanel]. It's used instead of a Compose
 * text field because Compose text re-lays-out the entire string on every edit, stalling on large
 * bodies; RSTA renders and tokenizes by viewport, so it stays smooth into the multi-MB range
 * (ADR-0020). Colors come from the theme and are re-applied in `update`, so it tracks light/dark.
 */
@Composable
internal actual fun CodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val wailo = LocalWailoColors.current
    // Ride the app-wide text scale (Cmd +/-) the host folds into the density's fontScale, so the
    // editor grows/shrinks with the rest of the UI even though Swing ignores Compose density.
    val fontSize = (13 * LocalDensity.current.fontScale).roundToInt().coerceIn(9, 40)

    // Hosting this editor behind a tab means it unmounts when another tab shows; capture the live text
    // into the state's seed on the way out so a remount restores the edits (and Save from another tab
    // reads them), rather than reseeding from the stale initial text.
    DisposableEffect(state) {
        onDispose { state.detach() }
    }

    SwingPanel(
        background = scheme.surface,
        modifier = modifier,
        factory = {
            val area = RSyntaxTextArea().apply {
                syntaxEditingStyle = language.styleFor(state.seedText)
                isCodeFoldingEnabled = state.seedText.length <= FOLDING_MAX_CHARS
                antiAliasingEnabled = true
                animateBracketMatching = false
                tabSize = 2
                text = state.seedText
                caretPosition = 0
            }
            // Guard the listener while we replace the document programmatically (Format/seed): those
            // aren't user edits and must not bump the revision or re-trigger validation as a "change".
            var suppress = false
            state.reader = { area.text }
            state.writer = { next ->
                if (area.text != next) {
                    suppress = true
                    area.text = next
                    area.caretPosition = 0
                    area.isCodeFoldingEnabled = next.length <= FOLDING_MAX_CHARS
                    area.syntaxEditingStyle = language.styleFor(next)
                    suppress = false
                }
            }
            area.document.addDocumentListener(object : DocumentListener {
                private fun changed() {
                    if (!suppress) state.revision++
                }
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
            RTextScrollPane(area).apply { lineNumbersEnabled = true }
        },
        update = { scrollPane ->
            val area = scrollPane.textArea as RSyntaxTextArea
            area.isEditable = !readOnly
            area.font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
            applyTheme(scrollPane, area, scheme, wailo)
        },
    )
}

// JSON highlighting for JSON; plain for everything else or for a document big enough that even
// per-line tokenizing isn't worth it. seedText size gates the "too big to bother" case.
private fun CodeLanguage.styleFor(text: String): String = when (this) {
    CodeLanguage.Json -> if (text.length <= FOLDING_MAX_CHARS * 10) SyntaxConstants.SYNTAX_STYLE_JSON else SyntaxConstants.SYNTAX_STYLE_NONE
    CodeLanguage.PlainText -> SyntaxConstants.SYNTAX_STYLE_NONE
}

private fun applyTheme(scrollPane: RTextScrollPane, area: RSyntaxTextArea, scheme: ColorScheme, wailo: WailoColors) {
    area.background = scheme.surface.toAwt()
    area.foreground = scheme.onSurface.toAwt()
    area.caretColor = scheme.onSurface.toAwt()
    area.currentLineHighlightColor = scheme.onSurface.copy(alpha = 0.05f).toAwt()
    area.selectionColor = scheme.primary.copy(alpha = 0.28f).toAwt()

    // Recolor the JSON token styles from the same intent tokens the JSON tree preview uses, so the
    // editor and the read-only viewer read as one language (BodyPreview: strings=success,
    // numbers=info, bool/null=warning, punctuation=muted).
    val syntax = area.syntaxScheme
    fun style(type: Int, color: Color) {
        syntax.getStyle(type)?.foreground = color.toAwt()
    }
    style(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, wailo.success)
    style(TokenTypes.LITERAL_CHAR, wailo.success)
    style(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, wailo.info)
    style(TokenTypes.LITERAL_NUMBER_FLOAT, wailo.info)
    style(TokenTypes.LITERAL_NUMBER_HEXADECIMAL, wailo.info)
    style(TokenTypes.LITERAL_BOOLEAN, wailo.warning)
    style(TokenTypes.RESERVED_WORD, wailo.warning)
    style(TokenTypes.RESERVED_WORD_2, wailo.warning)
    style(TokenTypes.SEPARATOR, scheme.onSurfaceVariant)
    style(TokenTypes.OPERATOR, scheme.onSurfaceVariant)
    style(TokenTypes.IDENTIFIER, scheme.onSurface)
    style(TokenTypes.VARIABLE, scheme.onSurface)
    style(TokenTypes.COMMENT_EOL, scheme.onSurfaceVariant)
    style(TokenTypes.COMMENT_MULTILINE, scheme.onSurfaceVariant)
    style(TokenTypes.ERROR_IDENTIFIER, scheme.error)
    style(TokenTypes.ERROR_STRING_DOUBLE, scheme.error)
    style(TokenTypes.ERROR_NUMBER_FORMAT, scheme.error)
    area.revalidate()
    area.repaint()

    scrollPane.gutter.apply {
        background = scheme.surfaceContainer.toAwt()
        lineNumberColor = scheme.onSurfaceVariant.toAwt()
        borderColor = scheme.outlineVariant.toAwt()
    }
}

// Compose Color carries straight ARGB; AWT reads the same int when told it has alpha, so translucent
// tokens (selection, current-line) composite over the editor background as intended.
private fun Color.toAwt(): AwtColor = AwtColor(toArgb(), true)
