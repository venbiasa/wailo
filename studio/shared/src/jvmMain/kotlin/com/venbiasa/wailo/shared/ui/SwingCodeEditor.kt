package com.venbiasa.wailo.shared.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

// Folding parses the whole document up front (the one part of RSTA that scales with size, not viewport),
// so it's off above this; highlighting stays on (it tokenizes lazily per visible line). ADR-0020.
private const val FOLDING_MAX_CHARS = 1_000_000

/**
 * The legacy Swing / RSyntaxTextArea editor, kept as a flag-gated fallback during the ADR-0023 transition
 * to the Compose-native [ComposeCodeEditor]. It mirrors the shared [CodeEditorState]: it seeds from
 * [CodeEditorState.currentText] and pushes every change back via [CodeEditorState.replaceAllFromWidget], so
 * Save/validation read the same text regardless of which editor is wired. Not the default — see
 * `USE_SWING_FALLBACK` in the JVM actual.
 */
@Composable
internal fun SwingCodeEditor(
    state: CodeEditorState,
    language: CodeLanguage,
    readOnly: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val wailo = LocalWailoColors.current
    val fontSize = (13 * LocalDensity.current.fontScale).roundToInt().coerceIn(9, 40)
    // Read so a programmatic setText (which bumps version) recomposes this and re-runs `update` to reseed.
    val version = state.version

    SwingPanel(
        background = scheme.surface,
        modifier = modifier,
        factory = {
            val seed = state.currentText()
            val area = RSyntaxTextArea().apply {
                syntaxEditingStyle = language.styleFor(seed)
                isCodeFoldingEnabled = seed.length <= FOLDING_MAX_CHARS
                antiAliasingEnabled = true
                animateBracketMatching = false
                tabSize = 2
                text = seed
                caretPosition = 0
            }
            var suppress = false
            area.document.addDocumentListener(object : DocumentListener {
                private fun changed() {
                    if (!suppress) state.replaceAllFromWidget(area.text)
                }
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
            area.putClientProperty(SUPPRESS_KEY, { value: Boolean -> suppress = value })
            RTextScrollPane(area).apply { lineNumbersEnabled = true }
        },
        update = { scrollPane ->
            version // reseed on programmatic setText
            val area = scrollPane.textArea as RSyntaxTextArea
            area.isEditable = !readOnly
            area.font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
            // Push a programmatic change (Format / seed-from-capture) into the widget without echoing it
            // back as a user edit; a user edit already left `area.text == currentText`, so this is a no-op then.
            val current = state.currentText()
            if (area.text != current) {
                @Suppress("UNCHECKED_CAST")
                val setSuppress = area.getClientProperty(SUPPRESS_KEY) as (Boolean) -> Unit
                setSuppress(true)
                area.text = current
                area.caretPosition = 0
                area.isCodeFoldingEnabled = current.length <= FOLDING_MAX_CHARS
                area.syntaxEditingStyle = language.styleFor(current)
                setSuppress(false)
            }
            applyTheme(scrollPane, area, scheme, wailo)
        },
    )
}

private const val SUPPRESS_KEY = "wailo.suppress"

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

private fun Color.toAwt(): AwtColor = AwtColor(toArgb(), true)

