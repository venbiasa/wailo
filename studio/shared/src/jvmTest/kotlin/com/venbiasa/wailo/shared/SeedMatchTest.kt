package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seed matching is the first matcher that runs on the desktop — Map Local and breakpoints only ever push
 * patterns and let the device decide. These pin it to the same semantics as the device's
 * `sdk-android/WailoMatching.kt`, because a seed is routinely copied from a Map Local rule and the pair
 * would be a trap if one matched and the other didn't.
 */
class SeedMatchTest {

    private fun seed(id: String, url: String, method: String = "") =
        SeedRuleDef(id = id, urlPattern = url, method = method)

    @Test
    fun wildcardMatchesAnyRunAndIsAnchoredToTheWholeUrl() {
        val seeds = listOf(seed("s", "https://api.example.com/v1/*"))
        assertEquals("s", seeds.firstMatch("https://api.example.com/v1/users", "GET")?.id)
        assertEquals("s", seeds.firstMatch("https://api.example.com/v1/a/b/c", "GET")?.id)
        // A prefix that stops short and a URL with a trailing extra both fail: the pattern is whole-string.
        assertNull(seeds.firstMatch("https://api.example.com/v1", "GET"))
        assertNull(seeds.firstMatch("https://other.example.com/v1/users", "GET"))
    }

    @Test
    fun literalSegmentsAreEscapedNotTreatedAsRegex() {
        // A `.` or `?` in the pattern is a literal, or every host pattern would match far too much.
        val seeds = listOf(seed("s", "https://a.example.com/x?y=1"))
        assertEquals("s", seeds.firstMatch("https://a.example.com/x?y=1", "GET")?.id)
        assertNull(seeds.firstMatch("https://aXexample.com/x?y=1", "GET"))
    }

    @Test
    fun urlIsCaseSensitiveButMethodIsNot() {
        val seeds = listOf(seed("s", "https://api.example.com/Users", "get"))
        assertEquals("s", seeds.firstMatch("https://api.example.com/Users", "GET")?.id)
        assertNull(seeds.firstMatch("https://api.example.com/users", "GET"))
    }

    @Test
    fun blankMethodMatchesAnyMethod() {
        val seeds = listOf(seed("s", "https://a/*"))
        assertEquals("s", seeds.firstMatch("https://a/x", "DELETE")?.id)
        assertEquals("s", seeds.firstMatch("https://a/x", "")?.id)
    }

    @Test
    fun methodNarrowsWhenSet() {
        val seeds = listOf(seed("s", "https://a/*", "POST"))
        assertEquals("s", seeds.firstMatch("https://a/x", "POST")?.id)
        assertNull(seeds.firstMatch("https://a/x", "GET"))
    }

    @Test
    fun emptyPatternNeverMatches() {
        // Not reachable from the editor (Save requires a pattern), but a decoded-from-prefs seed could
        // carry one — it must be inert, not a wildcard that swallows every hold.
        assertNull(listOf(seed("s", "")).firstMatch("https://a/x", "GET"))
        assertNull(listOf(seed("s", "")).firstMatch("", "GET"))
    }

    @Test
    fun firstMatchWinsInQueueOrder() {
        val seeds = listOf(
            seed("specific", "https://a/users", "GET"),
            seed("broad", "https://a/*"),
        )
        assertEquals("specific", seeds.firstMatch("https://a/users", "GET")?.id)
        // The broad seed still answers what the specific one doesn't — order is priority, not exclusivity.
        assertEquals("broad", seeds.firstMatch("https://a/orders", "GET")?.id)
        // ...and reversing the order makes the broad one shadow the specific one.
        assertEquals("broad", seeds.reversed().firstMatch("https://a/users", "GET")?.id)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(emptyList<SeedRuleDef>().firstMatch("https://a/x", "GET"))
        assertNull(listOf(seed("s", "https://b/*")).firstMatch("https://a/x", "GET"))
    }
}
