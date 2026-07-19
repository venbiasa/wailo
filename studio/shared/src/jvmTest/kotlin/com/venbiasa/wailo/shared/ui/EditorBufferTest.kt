package com.venbiasa.wailo.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorBufferTest {

    @Test
    fun emptyBufferHasOneEmptyLine() {
        val buffer = EditorBuffer("")
        assertEquals(1, buffer.lineCount)
        assertEquals("", buffer.line(0))
        assertEquals("", buffer.text())
        assertEquals(TextPos(0, 0), buffer.endPos())
    }

    @Test
    fun splitsOnNewlinesAndRoundTrips() {
        val buffer = EditorBuffer("a\nbb\nccc")
        assertEquals(3, buffer.lineCount)
        assertEquals("bb", buffer.line(1))
        assertEquals("a\nbb\nccc", buffer.text())
        assertEquals(TextPos(2, 3), buffer.endPos())
    }

    @Test
    fun trailingNewlineYieldsTrailingEmptyLine() {
        val buffer = EditorBuffer("a\n")
        assertEquals(2, buffer.lineCount)
        assertEquals("", buffer.line(1))
        assertEquals("a\n", buffer.text())
    }

    @Test
    fun insertWithinLineReturnsCaretAfterInsert() {
        val buffer = EditorBuffer("hello")
        val caret = buffer.replace(TextPos(0, 5), TextPos(0, 5), " world")
        assertEquals("hello world", buffer.text())
        assertEquals(TextPos(0, 11), caret)
    }

    @Test
    fun insertNewlineSplitsLine() {
        val buffer = EditorBuffer("abcd")
        val caret = buffer.replace(TextPos(0, 2), TextPos(0, 2), "\n")
        assertEquals("ab\ncd", buffer.text())
        assertEquals(TextPos(1, 0), caret)
    }

    @Test
    fun insertMultiLineTextSpansCorrectly() {
        val buffer = EditorBuffer("abcd")
        val caret = buffer.replace(TextPos(0, 2), TextPos(0, 2), "X\nY\nZ")
        assertEquals("abX\nY\nZcd", buffer.text())
        assertEquals(TextPos(2, 1), caret)
    }

    @Test
    fun deleteAcrossLinesJoins() {
        val buffer = EditorBuffer("abc\ndef\nghi")
        val caret = buffer.replace(TextPos(0, 1), TextPos(2, 1), "")
        assertEquals("ahi", buffer.text())
        assertEquals(TextPos(0, 1), caret)
    }

    @Test
    fun replaceIsOrderIndependent() {
        val forward = EditorBuffer("abcdef").also { it.replace(TextPos(0, 1), TextPos(0, 4), "X") }
        val reversed = EditorBuffer("abcdef").also { it.replace(TextPos(0, 4), TextPos(0, 1), "X") }
        assertEquals("aXef", forward.text())
        assertEquals("aXef", reversed.text())
    }

    @Test
    fun textInReadsSingleAndMultiLineRanges() {
        val buffer = EditorBuffer("abc\ndef\nghi")
        assertEquals("bc", buffer.textIn(TextPos(0, 1), TextPos(0, 3)))
        assertEquals("bc\ndef\ng", buffer.textIn(TextPos(0, 1), TextPos(2, 1)))
        // Order-independent.
        assertEquals("bc\ndef\ng", buffer.textIn(TextPos(2, 1), TextPos(0, 1)))
    }

    @Test
    fun clampKeepsPositionsInRange() {
        val buffer = EditorBuffer("ab\ncd")
        assertEquals(TextPos(1, 2), buffer.clamp(TextPos(9, 9)))
        assertEquals(TextPos(0, 0), buffer.clamp(TextPos(-1, -1)))
        assertEquals(TextPos(0, 2), buffer.clamp(TextPos(0, 5)))
    }

    @Test
    fun endAfterInsertMatchesReplaceCaret() {
        val buffer = EditorBuffer("abcd")
        val start = TextPos(0, 2)
        assertEquals(buffer.endAfterInsert(start, "XY"), TextPos(0, 4))
        assertEquals(buffer.endAfterInsert(start, "X\nYZ"), TextPos(1, 2))
    }

    @Test
    fun maxLineLengthTracksLongestLine() {
        assertEquals(5, EditorBuffer("a\nbbbbb\ncc").maxLineLength())
        assertEquals(0, EditorBuffer("").maxLineLength())
    }
}
