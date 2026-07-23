package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore
import com.venbiasa.wailo.shared.ui.ToolPanelLayout

/**
 * Persists the docked tool panel's width across launches, mirroring [TextScaleStore]/[ThemeStore]. The
 * width is stored as a *fraction* of the window (see [ToolPanelLayout]) so it scales with the window
 * rather than pinning to a fixed dp; the fraction is clamped on the way in and out. Host-owned because
 * persistence is a desktop concern; `shared` owns the ratio contract and its bounds.
 */
object PanelWidthStore {
    private const val KEY = "toolPanelWidthRatio"
    private val store = createKeyValueStore("desktop")

    /** Last saved fraction, clamped, or [ToolPanelLayout.DefaultWidthRatio] on first run. */
    fun load(): Float = ToolPanelLayout.coerceRatio(store.getFloat(KEY, ToolPanelLayout.DefaultWidthRatio))

    fun save(ratio: Float) = store.putFloat(KEY, ToolPanelLayout.coerceRatio(ratio))
}
