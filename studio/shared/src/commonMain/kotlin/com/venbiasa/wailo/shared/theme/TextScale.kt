package com.venbiasa.wailo.shared.theme

import kotlin.math.round

/**
 * User-adjustable text-size multiplier applied as the `fontScale` on the theme's density, so every
 * `sp` size flows through it while `dp` layout stays put (see [com.venbiasa.wailo.shared.WailoApp]).
 *
 * Bounds keep the extremes usable: below [Min] text is unreadable, and above [Max] the fixed 34.dp
 * table header would clip its single line. Steps snap to one decimal so repeated +/- doesn't drift
 * on binary-float rounding.
 */
object TextScale {
    const val Default = 1f
    const val Min = 0.8f
    const val Max = 1.8f
    const val Step = 0.1f

    fun increased(scale: Float): Float = coerce(scale + Step)

    fun decreased(scale: Float): Float = coerce(scale - Step)

    /**
     * Snap to one decimal and clamp into `[Min, Max]`. Also sanitizes a value restored from disk,
     * so a corrupt or out-of-range persisted scale can never push text off the usable range.
     */
    fun coerce(scale: Float): Float = (round(scale * 10f) / 10f).coerceIn(Min, Max)
}
