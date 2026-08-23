package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * A local copy of the capture filter — the allow/block host lists, each list's on/off switch, and the
 * feature master. The daemon owns the live filter (ADR-0082); this exists for one job, mirroring
 * [MapLocalStore]: re-seeding a daemon that starts with nothing, so a filter authored here is not lost
 * to a daemon restart. Whatever the daemon reports wins over it.
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
    // Read as fallback when the current key is empty, then dropped on the next save.
    private const val LEGACY_ALLOW_HOSTS_KEY = "captureAllowlistHosts"
    private const val BLOCK_ENABLED_KEY = "captureBlockEnabled"
    private const val BLOCK_HOSTS_KEY = "captureBlockHosts"
    private const val SEPARATOR = "\n"
    private val store = createKeyValueStore("desktop")

    fun load(): CaptureFilterState {
        val fromNew = readHosts(ALLOW_HOSTS_KEY)
        val allowHosts = fromNew.ifEmpty { readHosts(LEGACY_ALLOW_HOSTS_KEY) }
        val blockHosts = readHosts(BLOCK_HOSTS_KEY)
        val filter = CaptureFilterState(
            masterEnabled = store.getBoolean(MASTER_ENABLED_KEY, true),
            allowEnabled = (store.getBoolean(ALLOW_ENABLED_KEY, false) || fromNew.isEmpty()) &&
                allowHosts.isNotEmpty(),
            allowHosts = allowHosts,
            blockEnabled = store.getBoolean(BLOCK_ENABLED_KEY, false) && blockHosts.isNotEmpty(),
            blockHosts = blockHosts,
        )
        if (fromNew.isEmpty() && allowHosts.isNotEmpty()) save(filter)
        return filter
    }

    fun save(filter: CaptureFilterState) {
        store.putBoolean(MASTER_ENABLED_KEY, filter.masterEnabled)
        store.putBoolean(ALLOW_ENABLED_KEY, filter.allowEnabled)
        store.putString(ALLOW_HOSTS_KEY, filter.allowHosts.joinToString(SEPARATOR))
        store.putBoolean(BLOCK_ENABLED_KEY, filter.blockEnabled)
        store.putString(BLOCK_HOSTS_KEY, filter.blockHosts.joinToString(SEPARATOR))
        store.remove(LEGACY_ALLOW_HOSTS_KEY)
    }

    private fun readHosts(key: String): List<String> =
        store.getString(key, "")
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
