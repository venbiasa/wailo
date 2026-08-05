package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists how many captured exchanges the engine keeps, mirroring [PortStore]. Host-owned because the
 * engine lives at the desktop window; `shared` only renders the number and hands back a new one.
 *
 * [load] falls back to [WailoEngine.DEFAULT_MAX_RETAINED] both when nothing was ever saved and when what
 * was saved is out of range, so a hand-edited or corrupt preference can't launch the app holding one
 * request — or holding enough to exhaust the heap.
 */
object MaxRetainedStore {
    private const val KEY = "maxRetained"
    private val store = createKeyValueStore("desktop")

    fun load(): Int = store.getInt(KEY, WailoEngine.DEFAULT_MAX_RETAINED)
        .takeIf { it in WailoEngine.RETAINED_RANGE } ?: WailoEngine.DEFAULT_MAX_RETAINED

    fun save(max: Int) = store.putInt(KEY, max)
}
