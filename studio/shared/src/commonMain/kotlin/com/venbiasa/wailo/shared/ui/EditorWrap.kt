package com.venbiasa.wailo.shared.ui

import kotlin.math.floor

/** A point on the wrapped grid: which visual [row] of a physical line, and the column within that row. */
internal data class VisualPos(val row: Int, val colInRow: Int)

/**
 * How many monospace columns fit in [contentWidthPx] at [charWidthPx] — the width, in cells, at which a
 * physical line breaks onto its next visual row under soft wrap. At least 1 so a pathologically narrow
 * viewport (or an unmeasured one) still lays out a column per row instead of dividing by zero.
 */
internal fun wrapColumns(contentWidthPx: Float, charWidthPx: Float): Int {
    if (charWidthPx <= 0f) return 1
    return floor(contentWidthPx / charWidthPx).toInt().coerceAtLeast(1)
}

/**
 * The number of visual rows a [len]-char line occupies. [cols] `null` means wrap is off (always one row).
 * An empty line is still one row. A line whose length is an exact multiple of [cols] does NOT get a
 * trailing blank row — its end-of-line caret sits at the end of the last full row (see [caretVisualPos]).
 */
internal fun visualRowCount(len: Int, cols: Int?): Int {
    if (cols == null || len <= cols) return 1
    return (len + cols - 1) / cols
}

/**
 * Maps a caret column [col] on a [len]-char line to its (row, colInRow) under wrap. At end-of-line on a
 * length that's an exact multiple of [cols], the caret stays at the end of the last row rather than jumping
 * to a phantom column 0 of a new row — matching how editors render a wrapped caret. [cols] `null` = no wrap.
 */
internal fun caretVisualPos(col: Int, len: Int, cols: Int?): VisualPos {
    if (cols == null) return VisualPos(0, col)
    val rows = visualRowCount(len, cols)
    var row = col / cols
    if (row >= rows) row = rows - 1
    return VisualPos(row, col - row * cols)
}

/**
 * Inverse of [caretVisualPos]: the physical column a click at visual [row]/[colInRow] on a [len]-char line
 * maps to. Clamped so a click past a row's last character lands at that row's end (not the next row), and a
 * click below the last row lands on the last row. [cols] `null` = no wrap (clamp straight to line length).
 */
internal fun visualPosToCol(row: Int, colInRow: Int, len: Int, cols: Int?): Int {
    if (cols == null) return colInRow.coerceIn(0, len)
    val rows = visualRowCount(len, cols)
    val r = row.coerceIn(0, rows - 1)
    val rowStart = r * cols
    val rowLen = (len - rowStart).coerceIn(0, cols)
    return rowStart + colInRow.coerceIn(0, rowLen)
}
