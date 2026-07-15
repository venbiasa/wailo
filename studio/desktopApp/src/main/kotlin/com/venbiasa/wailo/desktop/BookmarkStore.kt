package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the set of bookmarked hosts across launches, mirroring [ThemeStore]/[TextScaleStore].
 * Host-owned because the bookmark state lives at the desktop window; `shared` only receives the list
 * plus add/remove callbacks and stays stateless over its inputs (ADR-0013).
 *
 * [createKeyValueStore] is primitive-only, so the set rides as a single newline-delimited string.
 * A host can never contain a newline, so it round-trips unambiguously; insertion order is preserved
 * so the bookmark bar's chips keep a stable, predictable order.
 */
object BookmarkStore {
    private const val KEY = "bookmarkedHosts"
    private const val SEPARATOR = "\n"
    private val store = createKeyValueStore("desktop")

    fun load(): List<String> =
        store.getString(KEY, "")
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun save(hosts: List<String>) = store.putString(KEY, hosts.joinToString(SEPARATOR))
}
