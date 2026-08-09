package com.venbiasa.wailo.shared.settings

import java.util.prefs.Preferences
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs against a throwaway node in the real per-user store, not a fake: the cap these tests are about
 * is enforced by [Preferences] itself, so a stub would pass while the app still crashed (ADR-0051).
 */
class PreferencesKeyValueStoreTest {

    private val parent = Preferences.userRoot().node("com/venbiasa/wailo")
    private val node = parent.node("test-" + Random.nextLong().toULong().toString(16))
    private val store = PreferencesKeyValueStore(node)

    @AfterTest
    fun removeTestNode() {
        node.removeNode()
        parent.flush()
    }

    @Test
    fun aValuePastThePreferencesCapRoundTrips() {
        // The regression: MapLocalStore.save threw "Value too long" once the authored layout outgrew
        // the cap, taking the window down on every subsequent edit.
        val layout = "rule-line|payload;".repeat(2_000)
        store.putString("layout", layout)
        assertEquals(layout, store.getString("layout", ""))
    }

    @Test
    fun aValueWrittenWholeBeforeChunkingExistedStillReads() {
        node.put("layout", "legacy-value")
        assertEquals("legacy-value", store.getString("layout", ""))
    }

    @Test
    fun shrinkingBackUnderTheCapLeavesNothingBehind() {
        store.putString("layout", "x".repeat(Preferences.MAX_VALUE_LENGTH * 3))
        store.putString("layout", "short")
        assertEquals("short", store.getString("layout", ""))
        assertEquals(listOf("layout"), node.keys().sorted())
    }

    @Test
    fun shrinkingToFewerChunksDropsTheOrphanedOnes() {
        store.putString("layout", "x".repeat(Preferences.MAX_VALUE_LENGTH * 3))
        val shorter = "y".repeat(Preferences.MAX_VALUE_LENGTH + 1)
        store.putString("layout", shorter)
        assertEquals(shorter, store.getString("layout", ""))
        assertEquals(listOf("layout.chunk.0", "layout.chunk.1", "layout.chunks"), node.keys().sorted())
    }

    @Test
    fun removeClearsAChunkedValue() {
        store.putString("layout", "x".repeat(Preferences.MAX_VALUE_LENGTH * 2))
        store.remove("layout")
        assertEquals("gone", store.getString("layout", "gone"))
        assertEquals(emptyList(), node.keys().toList())
    }

    @Test
    fun aSurrogatePairIsNeverSplitAcrossChunks() {
        // The leading ASCII char puts every pair on an odd offset, so the cap lands mid-pair and the
        // split has to back off by one; without that, both halves come back as replacement chars.
        val emoji = "a" + "\uD83D\uDE00".repeat(Preferences.MAX_VALUE_LENGTH)
        store.putString("layout", emoji)
        assertEquals(emoji, store.getString("layout", ""))
    }
}
