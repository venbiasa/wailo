package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the inspector's dark/light choice across launches, mirroring [TextScaleStore]. Host-owned
 * because the toggle state lives at the desktop window and `shared` only receives the resolved flag.
 * [load]'s default is seeded from the OS appearance at the call site, so first run follows the system
 * setting while every later launch honors the user's explicit toggle.
 */
object ThemeStore {
    private const val KEY = "darkTheme"
    private val store = createKeyValueStore("desktop")

    fun load(default: Boolean): Boolean = store.getBoolean(KEY, default)

    fun save(darkTheme: Boolean) = store.putBoolean(KEY, darkTheme)
}
