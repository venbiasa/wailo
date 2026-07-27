package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.MapLocalRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Map Local match-metadata store: enabled + method filter + URL wildcard, first-match wins (ADR-0019). */
class WailoRuleStoreTest {

    @After
    fun tearDown() = WailoRuleStore.replace(emptyList())

    private fun rule(
        id: String,
        pattern: String,
        enabled: Boolean = true,
        methods: List<String> = emptyList(),
    ) = MapLocalRule(id = id, enabled = enabled, url_pattern = pattern, methods = methods)

    @Test
    fun matchesEnabledWildcardRule() {
        WailoRuleStore.replace(listOf(rule("r1", "https://api.example.com/*")))
        assertEquals("r1", WailoRuleStore.match("https://api.example.com/users", "GET")?.id)
    }

    @Test
    fun noMatchWhenUrlDiffers() {
        WailoRuleStore.replace(listOf(rule("r1", "https://api.example.com/*")))
        assertNull(WailoRuleStore.match("https://other.example.com/users", "GET"))
    }

    @Test
    fun disabledRuleNeverMatches() {
        WailoRuleStore.replace(listOf(rule("r1", "*", enabled = false)))
        assertNull(WailoRuleStore.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun methodFilterIsRespectedCaseInsensitively() {
        WailoRuleStore.replace(listOf(rule("r1", "*", methods = listOf("post"))))
        assertEquals("r1", WailoRuleStore.match("https://api.example.com/users", "POST")?.id)
        assertNull(WailoRuleStore.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun emptyMethodsMatchesAnyMethod() {
        WailoRuleStore.replace(listOf(rule("r1", "*")))
        assertEquals("r1", WailoRuleStore.match("https://x", "DELETE")?.id)
    }

    @Test
    fun firstEnabledMatchWins() {
        WailoRuleStore.replace(
            listOf(
                rule("r1", "https://api.example.com/*", enabled = false),
                rule("r2", "https://api.example.com/*"),
                rule("r3", "*"),
            ),
        )
        assertEquals("r2", WailoRuleStore.match("https://api.example.com/users", "GET")?.id)
    }
}
