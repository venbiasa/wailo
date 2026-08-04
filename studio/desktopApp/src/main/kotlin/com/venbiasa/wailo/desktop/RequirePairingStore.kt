package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists whether WiFi devices must pair before they are let in (ADR-0040), mirroring [ThemeStore].
 *
 * Defaults off, which is the lenient mode: the common case is one developer's phone reaching one
 * developer's Mac at an address they typed themselves. Persisted because a security setting that
 * silently relaxes on the next launch is worse than not having offered it — a user who turned this on
 * for a shared network has to be able to trust that it stayed on.
 *
 * `java.util.prefs` is fine here where it would be wrong for the keys themselves: this is a policy
 * flag, not a secret, and the worst an attacker who can rewrite it could do is turn off a check they
 * were already able to bypass by being on the network in the first place.
 */
object RequirePairingStore {
    private const val KEY = "requirePairing"
    private val store = createKeyValueStore("desktop")

    fun load(): Boolean = store.getBoolean(KEY, false)

    fun save(enabled: Boolean) = store.putBoolean(KEY, enabled)
}
