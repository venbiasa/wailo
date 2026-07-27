package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.BreakpointRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Breakpoint store: enabled + at-least-one-phase + method filter + URL wildcard (ADR-0027). */
class WailoBreakpointStoreTest {

    @After
    fun tearDown() = WailoBreakpointStore.replace(emptyList())

    private fun rule(
        id: String,
        pattern: String,
        enabled: Boolean = true,
        methods: List<String> = emptyList(),
        onRequest: Boolean = false,
        onResponse: Boolean = false,
    ) = BreakpointRule(
        id = id,
        enabled = enabled,
        url_pattern = pattern,
        methods = methods,
        on_request = onRequest,
        on_response = onResponse,
    )

    @Test
    fun matchesAndReportsPhases() {
        WailoBreakpointStore.replace(listOf(rule("b1", "https://api.example.com/*", onRequest = true, onResponse = true)))
        val match = WailoBreakpointStore.match("https://api.example.com/users", "GET")
        assertEquals("b1", match?.ruleId)
        assertTrue(match!!.onRequest)
        assertTrue(match.onResponse)
    }

    @Test
    fun ruleWithNeitherPhaseNeverFires() {
        WailoBreakpointStore.replace(listOf(rule("b1", "*", onRequest = false, onResponse = false)))
        assertNull(WailoBreakpointStore.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun disabledRuleNeverMatches() {
        WailoBreakpointStore.replace(listOf(rule("b1", "*", enabled = false, onRequest = true)))
        assertNull(WailoBreakpointStore.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun methodFilterIsRespected() {
        WailoBreakpointStore.replace(listOf(rule("b1", "*", methods = listOf("POST"), onRequest = true)))
        assertNull(WailoBreakpointStore.match("https://api.example.com/users", "GET"))
        assertEquals("b1", WailoBreakpointStore.match("https://api.example.com/users", "POST")?.ruleId)
    }
}
