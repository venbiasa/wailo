package com.venbiasa.wailo.shared.settings

/**
 * Our own tiny persistent key-value contract — a "shared preferences" seam we own rather than a
 * third-party settings library, so the whole persistence surface is one interface plus one factory
 * per platform. Values survive process restarts; every getter returns [default] when the key is
 * absent. Keep it primitive-only: richer state belongs in a real store, not here.
 */
interface KeyValueStore {
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)

    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)

    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)

    fun remove(key: String)
}

/**
 * Opens the store for [namespace] (a logical bucket, e.g. `"desktop"`). Each target binds it to its
 * native store via `actual`: JVM → `java.util.prefs` today; add a source set + `actual` to extend
 * (e.g. iOS → `NSUserDefaults`, Android → `SharedPreferences`) without touching call sites.
 */
expect fun createKeyValueStore(namespace: String): KeyValueStore
