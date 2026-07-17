package com.venbiasa.wailo.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the window's floating size and position across launches, mirroring [ThemeStore]/
 * [TextScaleStore]. Host-owned because window geometry is a desktop-window concern the viewer never
 * sees.
 *
 * The "unset" sentinel is [Float.NaN], not a negative number, because a valid window origin can be
 * negative on multi-monitor setups (a display arranged to the left of, or above, the primary).
 */
object WindowStateStore {
    private const val KEY_WIDTH = "windowWidth"
    private const val KEY_HEIGHT = "windowHeight"
    private const val KEY_X = "windowX"
    private const val KEY_Y = "windowY"
    private val store = createKeyValueStore("desktop")

    /** Last saved size, or [default] on first run (either dimension missing). */
    fun loadSize(default: DpSize): DpSize {
        val width = store.getFloat(KEY_WIDTH, Float.NaN)
        val height = store.getFloat(KEY_HEIGHT, Float.NaN)
        return if (width.isNaN() || height.isNaN()) default else DpSize(width.dp, height.dp)
    }

    /** Last saved origin, or [WindowPosition.PlatformDefault] so the OS places the first window. */
    fun loadPosition(): WindowPosition {
        val x = store.getFloat(KEY_X, Float.NaN)
        val y = store.getFloat(KEY_Y, Float.NaN)
        return if (x.isNaN() || y.isNaN()) WindowPosition.PlatformDefault
        else WindowPosition.Absolute(x.dp, y.dp)
    }

    fun save(size: DpSize, position: WindowPosition) {
        store.putFloat(KEY_WIDTH, size.width.value)
        store.putFloat(KEY_HEIGHT, size.height.value)
        // Only an Absolute position carries coordinates; skip the pre-placement PlatformDefault so a
        // real origin is never clobbered by a sentinel.
        if (position is WindowPosition.Absolute) {
            store.putFloat(KEY_X, position.x.value)
            store.putFloat(KEY_Y, position.y.value)
        }
    }
}
