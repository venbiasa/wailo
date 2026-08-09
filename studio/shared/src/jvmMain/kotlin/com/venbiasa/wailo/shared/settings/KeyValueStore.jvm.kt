package com.venbiasa.wailo.shared.settings

import java.util.prefs.Preferences

// Root every namespace under the app's own node so keys never collide with other JVM apps' prefs.
actual fun createKeyValueStore(namespace: String): KeyValueStore =
    PreferencesKeyValueStore(Preferences.userRoot().node("com/venbiasa/wailo/$namespace"))

/**
 * JVM store backed by [Preferences] (per-user, OS-native: macOS plist / Windows registry / Linux
 * XML), so the desktop app owns no config file of its own and flushes are handled by the platform.
 *
 * Strings longer than [Preferences.MAX_VALUE_LENGTH] are split across numbered keys and rejoined on
 * read (ADR-0051). Callers persist whole layouts as one string — Map Local, breakpoints, seeds — and
 * those grow with every rule the user authors, while `Preferences.put` *throws* past the cap: without
 * splitting, authoring one rule too many turns every later save into a crash.
 */
internal class PreferencesKeyValueStore(private val prefs: Preferences) : KeyValueStore {
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.putFloat(key, value)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)

    override fun getString(key: String, default: String): String {
        val chunks = prefs.getInt(chunkCountKey(key), 0)
        // No count means the value was written whole — including by every build before splitting existed.
        if (chunks <= 0) return prefs.get(key, default)
        return (0 until chunks).joinToString("") { prefs.get(chunkKey(key, it), "") }
    }

    override fun putString(key: String, value: String) {
        val chunks = if (value.length <= maxValueLength) emptyList() else chunk(value)
        val previousChunks = prefs.getInt(chunkCountKey(key), 0)
        if (chunks.isEmpty()) {
            prefs.put(key, value)
            prefs.remove(chunkCountKey(key))
        } else {
            // Drop the whole-value key so prefs never hold a second, stale copy of the same setting.
            prefs.remove(key)
            chunks.forEachIndexed { index, part -> prefs.put(chunkKey(key, index), part) }
            prefs.putInt(chunkCountKey(key), chunks.size)
        }
        // Chunks the new value no longer reaches, because it shrank or now fits in a single key.
        (chunks.size until previousChunks).forEach { prefs.remove(chunkKey(key, it)) }
    }

    override fun remove(key: String) {
        val chunks = prefs.getInt(chunkCountKey(key), 0)
        (0 until chunks).forEach { prefs.remove(chunkKey(key, it)) }
        prefs.remove(chunkCountKey(key))
        prefs.remove(key)
    }
}

// Suffixed rather than prefixed so a chunked value sorts next to its own key when inspecting the
// platform store by hand. Both stay far inside MAX_KEY_LENGTH for the short keys the stores use.
private fun chunkKey(key: String, index: Int) = "$key.chunk.$index"

private fun chunkCountKey(key: String) = "$key.chunks"

private val maxValueLength = Preferences.MAX_VALUE_LENGTH

private fun chunk(value: String): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < value.length) {
        val limit = minOf(start + maxValueLength, value.length)
        // Never split a surrogate pair: each chunk is stored as a String of its own, and a lone half
        // is not text any of the platform backings can be trusted to hand back unchanged.
        val end = if (limit < value.length && value[limit - 1].isHighSurrogate()) limit - 1 else limit
        chunks += value.substring(start, end)
        start = end
    }
    return chunks
}
