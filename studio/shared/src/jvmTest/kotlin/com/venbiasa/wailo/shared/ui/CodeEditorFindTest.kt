package com.venbiasa.wailo.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The find bar's model half: matching (with the Aa toggle's case rule) and the replace-all sweep. Both are
// plain document logic, so they're testable without the composable that drives them.
class CodeEditorFindTest {

    @Test
    fun findIsCaseSensitiveByDefault() {
        val state = CodeEditorState("alpha\nAlpha")
        assertEquals(TextPos(1, 0) to TextPos(1, 5), state.find("Alpha", TextPos(0, 0), forward = true))
        assertNull(state.find("ALPHA", TextPos(0, 0), forward = true))
    }

    @Test
    fun findIgnoringCaseMatchesEitherCasing() {
        val state = CodeEditorState("alpha\nAlpha")
        assertEquals(
            TextPos(0, 0) to TextPos(0, 5),
            state.find("ALPHA", TextPos(0, 0), forward = true, ignoreCase = true),
        )
    }

    @Test
    fun findWrapsPastTheEnd() {
        val state = CodeEditorState("needle\nhaystack")
        assertEquals(TextPos(0, 0) to TextPos(0, 6), state.find("needle", TextPos(1, 0), forward = true))
    }

    @Test
    fun findBackwardTakesTheNearestPrecedingMatch() {
        val state = CodeEditorState("a x a x a")
        assertEquals(TextPos(0, 6) to TextPos(0, 7), state.find("x", TextPos(0, 8), forward = false))
    }

    @Test
    fun replaceAllRewritesEveryHitAsOneUndoStep() {
        val state = CodeEditorState("id: 1\nid: 2\nid: 3")
        assertEquals(3, state.replaceAll("id", "key"))
        assertEquals("key: 1\nkey: 2\nkey: 3", state.currentText())
        state.undo()
        assertEquals("id: 1\nid: 2\nid: 3", state.currentText())
    }

    @Test
    fun replaceAllHonoursCaseSensitivity() {
        val sensitive = CodeEditorState("Id id ID")
        assertEquals(1, sensitive.replaceAll("id", "x"))
        assertEquals("Id x ID", sensitive.currentText())

        val insensitive = CodeEditorState("Id id ID")
        assertEquals(3, insensitive.replaceAll("id", "x", ignoreCase = true))
        assertEquals("x x x", insensitive.currentText())
    }

    @Test
    fun replaceAllRefusesAMultiLineQuery() {
        val state = CodeEditorState("a\nb")
        assertEquals(0, state.replaceAll("a\nb", "c"))
        assertEquals("a\nb", state.currentText())
    }

    @Test
    fun replaceAllWithNoHitsLeavesNoEdit() {
        val state = CodeEditorState("abc")
        assertEquals(0, state.replaceAll("z", "y"))
        assertEquals("abc", state.currentText())
        assertEquals(0, state.revision)
    }

    @Test
    fun replaceAllKeepsTheCaretAndTheScrollWidthHonest() {
        val state = CodeEditorState("ab\ncd")
        state.moveCaret(TextPos(1, 1), extend = false)
        state.replaceAll("ab", "abcdefgh")
        // The caret must not be dragged to the end of the document (that would scroll the viewport away),
        // and the widened first line must reach maxLineLength even though the edit ended on the last one.
        assertEquals(TextPos(1, 1), state.caret)
        assertEquals(8, state.maxLineLength)
    }
}
