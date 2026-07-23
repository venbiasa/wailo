package com.venbiasa.wailo.shared.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizing for the docked tool panel (Map Local / capture allowlist), shared by the viewer that renders it
 * and the host that persists it. The width is stored as a fraction of the window width — so the panel
 * scales with the window instead of pinning to a fixed dp — but is always clamped when applied so the
 * panel keeps at least [MinWidth] and still leaves [MinContentWidth] for the traffic list. Mirrors
 * [com.venbiasa.wailo.shared.theme.TextScale]: pure bounds + coercion, no Compose state.
 */
object ToolPanelLayout {
    /** First-run width as a fraction of the window (min-clamped on narrow windows, see [widthFor]). */
    const val DefaultWidthRatio = 0.25f

    /** The panel never shrinks below this, even when the ratio would; it stays usable on small windows. */
    val MinWidth = 340.dp

    /** Width the traffic list is guaranteed to keep, which caps how wide the panel may grow. */
    val MinContentWidth = 320.dp

    /** Clamp a persisted or in-flight fraction to a sane range before it's stored or used. */
    fun coerceRatio(ratio: Float): Float = ratio.coerceIn(0f, 1f)

    /** The panel width for [ratio] at [totalWidth], clamped to keep both the panel and the content usable. */
    fun widthFor(ratio: Float, totalWidth: Dp): Dp =
        (totalWidth * coerceRatio(ratio)).coerceIn(MinWidth, maxWidth(totalWidth))

    /** The fraction to persist for a dragged [width] at [totalWidth] (clamped identically to [widthFor]). */
    fun ratioFor(width: Dp, totalWidth: Dp): Float {
        if (totalWidth <= 0.dp) return DefaultWidthRatio
        return coerceRatio(width.coerceIn(MinWidth, maxWidth(totalWidth)) / totalWidth)
    }

    // Widest the panel may get: whatever's left past the content floor, but never below MinWidth (on a
    // window too narrow to honor both, the panel wins and the content just scrolls).
    private fun maxWidth(totalWidth: Dp): Dp = (totalWidth - MinContentWidth).coerceAtLeast(MinWidth)
}
