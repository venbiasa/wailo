package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore
import com.venbiasa.wailo.shared.ui.TrafficColumnLayout

/**
 * Persists the traffic table's dragged column widths across launches, mirroring [PanelWidthStore].
 * Host-owned for the same reason the window geometry is: it is how this window is arranged, not
 * something a headless CLI or MCP session has any use for.
 *
 * One key per column rather than one encoded blob, so the prefs stay primitive ([createKeyValueStore]'s
 * contract) and a column added, renamed, or dropped later needs no migration — it simply reads back
 * missing and takes its built-in width. Only dragged columns are written, so the ones the user left
 * alone keep following the table's defaults instead of pinning today's.
 */
object ColumnWidthStore {
    private const val KEY_PREFIX = "trafficColumnWidth"
    private val store = createKeyValueStore("desktop")

    /** Saved dp widths by column key; a column never dragged is absent rather than defaulted. */
    fun load(): Map<String, Float> = buildMap {
        TrafficColumnLayout.Keys.forEach { key ->
            val saved = store.getFloat(KEY_PREFIX + key, 0f)
            if (saved > 0f) put(key, saved)
        }
    }

    fun save(widths: Map<String, Float>) {
        widths.forEach { (key, width) -> store.putFloat(KEY_PREFIX + key, width) }
    }
}
