package com.venbiasa.wailo.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
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

// Long enough that walking the caret down it carries both `{` and the array's opener off the top.
private const val ARRAY_OPENER = """  "items": ["""
private const val FIRST_ELEMENT = "    0,"
private val LONG_ARRAY = buildString {
    appendLine("{")
    appendLine(ARRAY_OPENER)
    repeat(60) { appendLine("    $it,") }
    appendLine("  ]")
    append("}")
}

// An element far enough down that array to only ever be on screen because something scrolled there.
private const val DEEP_QUERY = "57"
private const val DEEP_ELEMENT = "    $DEEP_QUERY,"

// Foldable blocks all the way down, unlike LONG_ARRAY's two openers at the very top: a fold-triggered jump
// only shows up where an arrow can be clicked with the document's first line far off screen.
private const val OBJECTS = 40
private const val FIRST_ID = """      "id": 0"""
private val OBJECT_LIST = buildString {
    appendLine("{")
    appendLine("""  "items": [""")
    repeat(OBJECTS) {
        appendLine("    {")
        appendLine("""      "id": $it""")
        appendLine("    },")
    }
    appendLine("  ]")
    append("}")
}

// The plain (non-diff) editor the sticky band is drawn over.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setArrayEditor(stickyRows: Int = StickyScopeRows.Default) {
    setContent {
        WailoTheme(darkTheme = false) {
            val state = rememberCodeEditorState(LONG_ARRAY)
            CompositionLocalProvider(LocalStickyScopeRows provides stickyRows) {
                Box(Modifier.size(640.dp, 400.dp)) {
                    CodeEditor(state, CodeLanguage.Json, readOnly = true, Modifier.fillMaxSize())
                }
            }
        }
    }
}

// One pane of a side-by-side diff over the same array: a decorated editor, so it takes the pair's scroll,
// wrap and fold rules instead of its own.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setDiffPane() {
    setContent {
        WailoTheme(darkTheme = false) {
            val state = rememberCodeEditorState(LONG_ARRAY)
            val folds = remember(state) { computeFoldRegions(state) }
            val listState = rememberLazyListState()
            val hScroll = rememberScrollState()
            Box(Modifier.size(640.dp, 400.dp)) {
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
                        otherLength = { 0 },
                        otherMaxLength = 0,
                        rowTint = { null },
                        spanRange = { null },
                        spanTint = Color.Transparent,
                        verticalScrollbar = true,
                        foldSpans = folds,
                        foldArrows = folds.mapValues { it.value.closeChar },
                        foldedRows = emptySet(),
                        onToggleFold = {},
                    ),
                )
            }
        }
    }
}

// Walks the caret down the array until its opening line has left the top of the viewport.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.scrollPastTheArrayOpener() {
    onNodeWithText(FIRST_ELEMENT).performClick()
    onNode(isFocused()).performKeyInput { repeat(40) { pressKey(Key.DirectionDown) } }
    waitForIdle()
    assertEquals(0, onAllNodesWithText(FIRST_ELEMENT).fetchSemanticsNodes().size, "the body scrolled past")
}

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
     * A fold leaves the reader where they were. The caret-follow effect used to be keyed on the fold set and
     * on focus, and clicking an arrow changes both without moving the caret — so every collapse chased a caret
     * still at line 0 of a body nobody had typed in, and the viewport snapped back to the top of the document.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun collapsingABlockKeepsTheScrollPosition() = runComposeUiTest {
        setContent {
            WailoTheme(darkTheme = false) {
                val state = rememberCodeEditorState(OBJECT_LIST)
                Box(Modifier.size(640.dp, 400.dp)) {
                    CodeEditor(state, CodeLanguage.Json, readOnly = true, Modifier.fillMaxSize())
                }
            }
        }

        // Which objects are on screen, top-down: enough to say both that the viewport held still and that the
        // click did fold something.
        fun visibleIds(): List<Int> =
            (0 until OBJECTS).filter { onAllNodesWithText("""      "id": $it""").fetchSemanticsNodes().isNotEmpty() }

        // The wheel rather than the keyboard: walking the caret down would scroll the list legitimately, which
        // is the one thing this test must not do. Over-scroll just clamps at the end of the document.
        onNodeWithText(FIRST_ID).performMouseInput { scroll(20f) }
        waitForIdle()
        val top = visibleIds().first()
        assertTrue(top > 0, "the top of the document scrolled away, was at object $top")

        // Mid-viewport, not the topmost arrow: the sticky band paints over the first rows of the list, so a
        // click up there lands on a pinned line and scrolls back to it — the band's own behavior, not a fold.
        val arrows = onAllNodesWithContentDescription("Collapse block")
        arrows[arrows.fetchSemanticsNodes().size / 2].performClick()
        waitForIdle()

        assertEquals(top, visibleIds().first(), "the fold left the viewport where it was")
        // Guards the assertion above against passing on a click that did nothing at all: the folded opener
        // now offers the other arrow, and it is only on screen because the viewport stayed put.
        assertEquals(1, onAllNodesWithContentDescription("Expand block").fetchSemanticsNodes().size, "it folded")
    }

    /**
     * The point of the sticky header: the key an array belongs to is still readable once the array is long
     * enough to have pushed its own opening line off the top.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun parentLinesStayPinnedOnceTheyScrollAway() = runComposeUiTest {
        setArrayEditor()
        scrollPastTheArrayOpener()

        val opener = onNodeWithText(ARRAY_OPENER).getUnclippedBoundsInRoot()
        // The band's only row here, the root `{` being left out of it, so the opener sits at the very top of
        // the editor — nowhere near line 2's own place in a document scrolled forty lines on.
        assertTrue(opener.top < opener.height * 2, "the opener should be pinned at the top, was ${opener.top}")
    }

    /**
     * Clicking a pinned line goes back to it — and it has to *stay* there. The band deliberately doesn't take
     * focus off the editor, because the caret-follow effect would then fire and drag the list straight back
     * to wherever the caret was left.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingAPinnedLineScrollsBackToIt() = runComposeUiTest {
        setArrayEditor()
        scrollPastTheArrayOpener()

        onNodeWithText(ARRAY_OPENER).performClick()
        waitForIdle()
        assertEquals(1, onAllNodesWithText(FIRST_ELEMENT).fetchSemanticsNodes().size, "back at the array")
    }

    /**
     * The band is opaque and sits over the list, so it is hit before the rows underneath it are: it has to
     * hand the wheel on, or scrolling would die across the whole top of the editor.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theBandDoesNotSwallowTheWheel() = runComposeUiTest {
        setArrayEditor()
        scrollPastTheArrayOpener()

        // Far enough back to reach the top whatever a wheel notch is worth here; over-scroll just clamps.
        onNodeWithText(ARRAY_OPENER).performMouseInput { scroll(-100f) }
        waitForIdle()
        assertEquals(1, onAllNodesWithText(FIRST_ELEMENT).fetchSemanticsNodes().size, "the wheel reached the list")
    }

    /**
     * Zero is a real setting, not a degenerate one: the band trades rows of the body for context, and a
     * reader who does not want that trade turns it off rather than avoiding deep bodies.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDepthOfZeroPinsNothing() = runComposeUiTest {
        setArrayEditor(stickyRows = StickyScopeRows.Min)
        scrollPastTheArrayOpener()

        assertEquals(0, onAllNodesWithText(ARRAY_OPENER).fetchSemanticsNodes().size)
    }

    /**
     * A diff pane is excluded on purpose: its rows are the pair's grid rather than one document's, and a
     * pinned copy would sit over the row tint that says what changed.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDiffPaneNeverPinsParentLines() = runComposeUiTest {
        setDiffPane()
        scrollPastTheArrayOpener()

        assertEquals(0, onAllNodesWithText(ARRAY_OPENER).fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun findInOneDiffPaneKeepsBothRowGridsLevel() = runComposeUiTest {
        val leftLine = "left row"
        val rightLine = "right row"
        setContent {
            WailoTheme(darkTheme = false) {
                val leftState = rememberCodeEditorState("$leftLine\nleft second")
                val rightState = rememberCodeEditorState("$rightLine\nright second")
                val leftList = rememberLazyListState()
                val rightList = rememberLazyListState()
                val hScroll = rememberScrollState()
                val findBarSlot = remember { SynchronizedFindBarSlot() }
                Row(Modifier.size(640.dp, 300.dp)) {
                    CodeEditor(
                        state = leftState,
                        language = CodeLanguage.PlainText,
                        readOnly = true,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        decor = CodeEditorDecor(
                            listState = leftList,
                            hScroll = hScroll,
                            wrap = true,
                            lineNumber = { it + 1 },
                            otherLength = { rightState.lineAt(it).length },
                            otherMaxLength = rightState.maxLineLength,
                            rowTint = { null },
                            spanRange = { null },
                            spanTint = Color.Transparent,
                            verticalScrollbar = false,
                            foldSpans = emptyMap(),
                            foldArrows = emptyMap(),
                            foldedRows = emptySet(),
                            onToggleFold = {},
                            findBarSlot = findBarSlot,
                        ),
                    )
                    CodeEditor(
                        state = rightState,
                        language = CodeLanguage.PlainText,
                        readOnly = true,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        decor = CodeEditorDecor(
                            listState = rightList,
                            hScroll = hScroll,
                            wrap = true,
                            lineNumber = { it + 1 },
                            otherLength = { leftState.lineAt(it).length },
                            otherMaxLength = leftState.maxLineLength,
                            rowTint = { null },
                            spanRange = { null },
                            spanTint = Color.Transparent,
                            verticalScrollbar = true,
                            foldSpans = emptyMap(),
                            foldArrows = emptyMap(),
                            foldedRows = emptySet(),
                            onToggleFold = {},
                            findBarSlot = findBarSlot,
                        ),
                    )
                }
            }
        }

        fun topOf(text: String) = onNodeWithText(text).getUnclippedBoundsInRoot().top
        val initialTop = topOf(leftLine)
        assertEquals(initialTop, topOf(rightLine))

        onNodeWithText(leftLine).performClick()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        waitForIdle()

        assertEquals(topOf(leftLine), topOf(rightLine), "opening Find must move both row grids together")

        onNodeWithContentDescription("Close find").performClick()
        waitForIdle()
        assertEquals(initialTop, topOf(leftLine))
        assertEquals(initialTop, topOf(rightLine))
    }

    /**
     * Find has to reach its hit in a diff pane too. The bar keeps focus in its query field, and an unfocused
     * pane is barred from chasing its caret — that guard is what stops the idle side of a diff from dragging
     * the shared scroll position back to its own document's top, and it used to swallow this scroll as well:
     * the match was selected forty rows below the viewport, so the bar read as if it had found nothing.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun findInADiffPaneScrollsToItsMatch() = runComposeUiTest {
        setDiffPane()

        // Cmd/Ctrl+F is an editor shortcut, so a click on a line is how the pane gets focus first.
        onNodeWithText(FIRST_ELEMENT).performClick()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        onNode(hasSetTextAction()).performTextInput(DEEP_QUERY)
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, onAllNodesWithText(DEEP_ELEMENT).fetchSemanticsNodes().size, "the hit is on screen")
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
