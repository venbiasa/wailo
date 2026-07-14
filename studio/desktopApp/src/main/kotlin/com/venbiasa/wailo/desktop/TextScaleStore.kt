package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore
import com.venbiasa.wailo.shared.theme.TextScale

/**
 * Persists the text-size multiplier across launches. Backed by the shared `KeyValueStore` contract
 * (JVM → java.util.prefs today; other platforms plug in via its expect/actual factory). Host-owned
 * because the scale state and the Cmd +/- intercept live at the desktop window; `shared` only
 * provides the storage seam and the bounds ([TextScale]).
 */
object TextScaleStore {
    private const val KEY = "textScale"
    private val store = createKeyValueStore("desktop")

    /** Last saved scale, sanitized back into bounds, or [TextScale.Default] on first run. */
    fun load(): Float = TextScale.coerce(store.getFloat(KEY, TextScale.Default))

    fun save(scale: Float) = store.putFloat(KEY, scale)
}
