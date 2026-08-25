package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.venbiasa.wailo.shared.theme.WailoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The two documents are deliberately the same length: a stale fold map from the other one would still have
// real rows to draw its arrows on, so the arrow count alone tells the two apart.
private val NESTED = """
    {
      "a": [
        1
      ]
    }
""".trimIndent()

private val FLAT = """
    {
      "a": 1,
      "b": [],
      "c": 2
    }
""".trimIndent()

/** Behavior of the [CodeEditor] composable itself; the document model is covered by `CodeEditorFindTest`. */
class CodeEditorSurfaceTest {

    /**
     * Callers re-seed the editor by handing it a whole new [CodeEditorState] (the body preview does it per
     * row, the breakpoint editor per paused exchange), and every instance starts at version 0 — so anything
     * the editor caches per document has to key on the instance too. It didn't, and the arrows of the body
     * you looked at last stayed on screen over the next one.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun foldArrowsFollowASwappedDocument() = runComposeUiTest {
        val nested = mutableStateOf(true)
        setContent {
            WailoTheme(darkTheme = false) {
                val showNested = nested.value
                val state = remember(showNested) { CodeEditorState(if (showNested) NESTED else FLAT) }
                Box(Modifier.size(640.dp, 400.dp)) {
                    CodeEditor(state, CodeLanguage.Json, readOnly = true, Modifier.fillMaxSize())
                }
            }
        }

        fun arrows() = onAllNodesWithContentDescription("Collapse block").fetchSemanticsNodes().size
        assertEquals(2, arrows(), "the object and the array it holds each fold")

        nested.value = false
        waitForIdle()
        assertEquals(1, arrows(), "only the object folds; `[]` sits on one line")
    }

    /** Reopening the bar starts a fresh hunt, rather than re-presenting a query aimed at an older body. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun closingFindEmptiesTheQuery() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                val state = rememberCodeEditorState("needle in a haystack")
                Box(Modifier.size(640.dp, 300.dp)) {
                    CodeEditor(state, CodeLanguage.PlainText, readOnly = true, Modifier.fillMaxSize())
                }
            }
        }

        // Cmd/Ctrl+F is an editor shortcut, so the editor has to hold focus first — a click on a line is how
        // a user gets there.
        onNodeWithText("needle in a haystack").performClick()
        fun openFind() = onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }

        openFind()
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithContentDescription("Close find").performClick()
        openFind()

        val query = onNode(hasSetTextAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("", query)
    }

    /**
     * A diff row is as tall as the taller of the two panes, so the side with the shorter line gets filler rows
     * below the end of its own text — rows it has no columns on. The taller side's height has to be honored,
     * or the columns drift, without this pane's line being sliced onto rows that start past its end.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aWrappedPaneKeepsStepWithATallerNeighbour() = runComposeUiTest {
        val first = """{ "a": 1 }"""
        val second = """{ "b": 2 }"""
        setContent {
            WailoTheme(darkTheme = false) {
                val state = rememberCodeEditorState("$first\n$second")
                val listState = rememberLazyListState()
                val hScroll = rememberScrollState()
                Box(Modifier.size(320.dp, 400.dp)) {
                    CodeEditor(
                        state = state,
                        language = CodeLanguage.Json,
                        readOnly = true,
                        modifier = Modifier.fillMaxSize(),
                        decor = CodeEditorDecor(
                            listState = listState,
                            hScroll = hScroll,
                            wrap = true,
                            lineNumber = { it + 1 },
                            // The opposite pane's first row holds a line long enough to wrap several times;
                            // its second is empty, so only the first row grows.
                            otherLength = { if (it == 0) 200 else 0 },
                            otherMaxLength = 200,
                            rowTint = { null },
                            spanRange = { null },
                            spanTint = Color.Transparent,
                            verticalScrollbar = true,
                            foldSpans = emptyMap(),
                            foldArrows = emptyMap(),
                            foldedRows = emptySet(),
                            onToggleFold = {},
                        ),
                    )
                }
            }
        }

        val firstRow = onNodeWithText(first).getUnclippedBoundsInRoot()
        val secondRow = onNodeWithText(second).getUnclippedBoundsInRoot()
        // The gap proves the taller side's height was honored — one line apart would mean this pane sized the
        // row to its own single wrapped row and the two columns had drifted.
        assertTrue(
            secondRow.top - firstRow.top > firstRow.height * 3,
            "row 1 should be as tall as the wrapped neighbour, was ${secondRow.top - firstRow.top}",
        )
    }
}
