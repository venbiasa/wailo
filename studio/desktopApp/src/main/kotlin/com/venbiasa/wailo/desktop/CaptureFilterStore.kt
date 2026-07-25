package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the capture filter — the allow/block host lists and each list's on/off switch — across
 * launches, mirroring [BookmarkStore]. Host-owned because the filter lives at the desktop window and the
 * engine pushes it to devices, which decide per request whether to capture the whole exchange (ADR-0029);
 * `shared` only renders it and hands back a new [CaptureFilterState], staying stateless over its inputs.
 *
 * [createKeyValueStore] is primitive-only, so each list rides as a single newline-delimited string (a
 * host pattern can never contain a newline, so it round-trips unambiguously and insertion order is
 * preserved) and each switch as a boolean. Loading re-asserts the "empty list ⇒ switch off" invariant so
 * a hand-edited or partially-written pref can never come back as an enabled-but-empty list.
 */
object CaptureFilterStore {
    // Defaults on (true) so an install with no saved value behaves exactly as before the master switch
    // existed — the filter is live and governed solely by the two list switches.
    private const val MASTER_ENABLED_KEY = "captureFilterEnabled"
    private const val ALLOW_ENABLED_KEY = "captureAllowEnabled"
    private const val ALLOW_HOSTS_KEY = "captureAllowHosts"
    private const val BLOCK_ENABLED_KEY = "captureBlockEnabled"
    private const val BLOCK_HOSTS_KEY = "captureBlockHosts"
    private const val SEPARATOR = "\n"
    private val store = createKeyValueStore("desktop")

    fun load(): CaptureFilterState {
        val allowHosts = readHosts(ALLOW_HOSTS_KEY)
        val blockHosts = readHosts(BLOCK_HOSTS_KEY)
        return CaptureFilterState(
            masterEnabled = store.getBoolean(MASTER_ENABLED_KEY, true),
            allowEnabled = store.getBoolean(ALLOW_ENABLED_KEY, false) && allowHosts.isNotEmpty(),
            allowHosts = allowHosts,
            blockEnabled = store.getBoolean(BLOCK_ENABLED_KEY, false) && blockHosts.isNotEmpty(),
            blockHosts = blockHosts,
        )
    }

    fun save(filter: CaptureFilterState) {
        store.putBoolean(MASTER_ENABLED_KEY, filter.masterEnabled)
        store.putBoolean(ALLOW_ENABLED_KEY, filter.allowEnabled)
        store.putString(ALLOW_HOSTS_KEY, filter.allowHosts.joinToString(SEPARATOR))
        store.putBoolean(BLOCK_ENABLED_KEY, filter.blockEnabled)
        store.putString(BLOCK_HOSTS_KEY, filter.blockHosts.joinToString(SEPARATOR))
    }

    private fun readHosts(key: String): List<String> =
        store.getString(key, "")
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
