package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the set of "unlocked" host patterns whose bodies are captured, across launches, mirroring
 * [BookmarkStore]. Host-owned because the allowlist lives at the desktop window and is pushed to
 * devices by the engine; `shared` only receives the list plus unlock/lock callbacks and stays
 * stateless over its inputs. Metadata is always captured — this list gates only body bytes.
 *
 * [createKeyValueStore] is primitive-only, so the set rides as a single newline-delimited string.
 * A host pattern can never contain a newline, so it round-trips unambiguously; insertion order is
 * preserved so the manager's list keeps a stable, predictable order.
 */
object CaptureAllowlistStore {
    private const val KEY = "captureAllowlistHosts"
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
