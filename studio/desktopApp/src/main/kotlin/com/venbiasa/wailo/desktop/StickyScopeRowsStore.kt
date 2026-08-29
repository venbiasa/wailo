package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore
import com.venbiasa.wailo.shared.ui.StickyScopeRows

/**
 * Persists how deep the code editor's sticky header pins. Host-owned like [TextScaleStore]: the choice
 * belongs to this window across launches, and `shared` supplies only the storage seam and the bounds
 * ([StickyScopeRows]).
 *
 * [load] clamps what it reads rather than trusting it, so a hand-edited or corrupt preference can't put a
 * band taller than the pane it is drawn over.
 */
object StickyScopeRowsStore {
    private const val KEY = "stickyScopeRows"
    private val store = createKeyValueStore("desktop")

    fun load(): Int = StickyScopeRows.coerce(store.getInt(KEY, StickyScopeRows.Default))

    fun save(rows: Int) = store.putInt(KEY, rows)
}
