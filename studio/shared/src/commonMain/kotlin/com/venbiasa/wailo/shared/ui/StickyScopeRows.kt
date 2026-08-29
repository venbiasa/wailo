package com.venbiasa.wailo.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How many parent lines the code editor's sticky header may pin at once, shared by the editors that draw
 * the band and the host that persists the choice. Mirrors [ToolPanelLayout]: pure bounds + coercion, no
 * Compose state.
 *
 * Bounded rather than free because the band is an overlay, so its rows are spent, not added: each one
 * covers a line of the body and widens the clearance the editor keeps between the caret and the top of the
 * viewport, which is what stops a jump to the caret from parking it behind the band. Depth also pays less
 * the deeper it goes — [ScopeNesting.ancestorsOf] cuts the chain at the *deep* end so the band holds still,
 * so the rows a larger number buys are the outermost containers, which are the ones the reader is least
 * likely to have forgotten. [Max] is past the nesting real bodies have while still leaving a pane worth
 * reading; the editor then holds the band to half of whatever pane it is actually in.
 */
object StickyScopeRows {
    const val Default = 5

    /** No band at all: nothing is pinned, and the editor reserves no rows for one. */
    const val Min = 0

    const val Max = 10

    /** Clamp a persisted or in-flight depth, so a hand-edited preference can't outgrow the pane. */
    fun coerce(rows: Int): Int = rows.coerceIn(Min, Max)
}

/**
 * The depth every editor's sticky band resolves against. A CompositionLocal rather than a parameter
 * because the editor is several panels down from the window that owns the choice, and the setting is of no
 * concern to anything in between.
 */
val LocalStickyScopeRows = staticCompositionLocalOf { StickyScopeRows.Default }
