package com.venbiasa.wailo.shared.settings

import java.util.prefs.Preferences

// Root every namespace under the app's own node so keys never collide with other JVM apps' prefs.
actual fun createKeyValueStore(namespace: String): KeyValueStore =
    PreferencesKeyValueStore(Preferences.userRoot().node("com/venbiasa/wailo/$namespace"))

/**
 * JVM store backed by [Preferences] (per-user, OS-native: macOS plist / Windows registry / Linux
 * XML), so the desktop app owns no config file of its own and flushes are handled by the platform.
 */
private class PreferencesKeyValueStore(private val prefs: Preferences) : KeyValueStore {
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.putFloat(key, value)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)

    override fun getString(key: String, default: String): String = prefs.get(key, default)
    override fun putString(key: String, value: String) = prefs.put(key, value)

    override fun remove(key: String) = prefs.remove(key)
}
