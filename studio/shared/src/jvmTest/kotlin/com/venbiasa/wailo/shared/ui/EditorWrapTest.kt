package com.venbiasa.wailo.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorWrapTest {

    @Test
    fun wrapColumnsFloorsAndClampsToAtLeastOne() {
        assertEquals(10, wrapColumns(contentWidthPx = 105f, charWidthPx = 10f))
        assertEquals(1, wrapColumns(contentWidthPx = 3f, charWidthPx = 10f))
        assertEquals(1, wrapColumns(contentWidthPx = 100f, charWidthPx = 0f))
    }

    @Test
    fun visualRowCountWrapOffIsAlwaysOne() {
        assertEquals(1, visualRowCount(len = 0, cols = null))
        assertEquals(1, visualRowCount(len = 5000, cols = null))
    }

    @Test
    fun visualRowCountWrapsByColumns() {
        assertEquals(1, visualRowCount(len = 0, cols = 10))
        assertEquals(1, visualRowCount(len = 10, cols = 10)) // exact fit is one row, no blank trailer
        assertEquals(2, visualRowCount(len = 11, cols = 10))
        assertEquals(2, visualRowCount(len = 20, cols = 10))
        assertEquals(3, visualRowCount(len = 21, cols = 10))
    }

    @Test
    fun caretVisualPosWrapOffIsFlat() {
        assertEquals(VisualPos(0, 0), caretVisualPos(col = 0, len = 40, cols = null))
        assertEquals(VisualPos(0, 37), caretVisualPos(col = 37, len = 40, cols = null))
    }

    @Test
    fun caretVisualPosSplitsAcrossRows() {
        assertEquals(VisualPos(0, 0), caretVisualPos(col = 0, len = 25, cols = 10))
        assertEquals(VisualPos(0, 9), caretVisualPos(col = 9, len = 25, cols = 10))
        assertEquals(VisualPos(1, 0), caretVisualPos(col = 10, len = 25, cols = 10))
        assertEquals(VisualPos(2, 5), caretVisualPos(col = 25, len = 25, cols = 10))
    }

    @Test
    fun caretAtExactMultipleEndStaysOnLastRow() {
        // len == 2*cols; the end caret sits at the end of row 1, not column 0 of a phantom row 2.
        assertEquals(VisualPos(1, 10), caretVisualPos(col = 20, len = 20, cols = 10))
        // A single full row: end caret is at the end of row 0.
        assertEquals(VisualPos(0, 10), caretVisualPos(col = 10, len = 10, cols = 10))
    }

    @Test
    fun visualPosToColIsInverseOfCaretVisualPos() {
        val cols = 10
        val len = 25
        for (col in 0..len) {
            val vp = caretVisualPos(col, len, cols)
            assertEquals(col, visualPosToCol(vp.row, vp.colInRow, len, cols), "round-trip col=$col")
        }
    }

    @Test
    fun visualPosToColClampsClicksPastRowAndBelowContent() {
        val cols = 10
        val len = 25
        // Click far to the right of row 0 lands at that row's end (col 10), not on row 1.
        assertEquals(10, visualPosToCol(row = 0, colInRow = 99, len = len, cols = cols))
        // Click far right of the last (partial) row lands at end-of-line.
        assertEquals(25, visualPosToCol(row = 2, colInRow = 99, len = len, cols = cols))
        // Click below the last row is clamped onto the last row.
        assertEquals(25, visualPosToCol(row = 99, colInRow = 99, len = len, cols = cols))
    }

    @Test
    fun visualPosToColWrapOffClampsToLineLength() {
        assertEquals(0, visualPosToCol(row = 0, colInRow = -3, len = 5, cols = null))
        assertEquals(5, visualPosToCol(row = 0, colInRow = 99, len = 5, cols = null))
        assertEquals(3, visualPosToCol(row = 0, colInRow = 3, len = 5, cols = null))
    }
}
