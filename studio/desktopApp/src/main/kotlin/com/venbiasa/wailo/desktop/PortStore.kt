package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the port the capture server binds, mirroring [ThemeStore]. Host-owned because the engine and
 * its lifecycle live at the desktop window; `shared` only renders the number and hands back a new one.
 *
 * [load] falls back to [WailoEngine.DEFAULT_PORT] both when nothing was ever saved and when what was saved
 * is out of range, so a hand-edited or corrupt preference can't leave the app unable to bind at launch.
 */
object PortStore {
    private const val KEY = "listenPort"
    private val store = createKeyValueStore("desktop")

    fun load(): Int = store.getInt(KEY, WailoEngine.DEFAULT_PORT)
        .takeIf { it in WailoEngine.PORT_RANGE } ?: WailoEngine.DEFAULT_PORT

    fun save(port: Int) = store.putInt(KEY, port)
}
