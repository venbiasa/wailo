package com.venbiasa.wailo.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists a window's floating size and position across launches, mirroring [ThemeStore]/[TextScaleStore].
 * Host-owned because window geometry is a desktop-window concern the viewer never sees. Parameterized by a
 * [keyPrefix] so each window keeps its own geometry under distinct keys in the one shared prefs store —
 * see the [Main] and [Breakpoint] instances.
 *
 * The "unset" sentinel is [Float.NaN], not a negative number, because a valid window origin can be
 * negative on multi-monitor setups (a display arranged to the left of, or above, the primary).
 */
class WindowStateStore(keyPrefix: String) {
    private val keyWidth = "${keyPrefix}Width"
    private val keyHeight = "${keyPrefix}Height"
    private val keyX = "${keyPrefix}X"
    private val keyY = "${keyPrefix}Y"
    private val store = createKeyValueStore("desktop")

    /** Last saved size, or [default] on first run (either dimension missing). */
    fun loadSize(default: DpSize): DpSize {
        val width = store.getFloat(keyWidth, Float.NaN)
        val height = store.getFloat(keyHeight, Float.NaN)
        return if (width.isNaN() || height.isNaN()) default else DpSize(width.dp, height.dp)
    }

    /** Last saved origin, or [WindowPosition.PlatformDefault] so the OS places the first window. */
    fun loadPosition(): WindowPosition {
        val x = store.getFloat(keyX, Float.NaN)
        val y = store.getFloat(keyY, Float.NaN)
        return if (x.isNaN() || y.isNaN()) WindowPosition.PlatformDefault
        else WindowPosition.Absolute(x.dp, y.dp)
    }

    fun save(size: DpSize, position: WindowPosition) {
        store.putFloat(keyWidth, size.width.value)
        store.putFloat(keyHeight, size.height.value)
        // Only an Absolute position carries coordinates; skip the pre-placement PlatformDefault so a
        // real origin is never clobbered by a sentinel.
        if (position is WindowPosition.Absolute) {
            store.putFloat(keyX, position.x.value)
            store.putFloat(keyY, position.y.value)
        }
    }

    companion object {
        /** The main viewer window — keeps the original keys ("windowWidth"/…) so saved geometry survives. */
        val Main = WindowStateStore("window")

        /** The breakpoint inspector window (ADR-0034): persisted like the main window, under its own keys. */
        val Breakpoint = WindowStateStore("breakpointWindow")
    }
}
