package com.venbiasa.wailo.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseArgsTest {

    @Test
    fun parsesServeAndFlags() {
        val parsed = parseArgs(arrayOf("serve", "--port", "9000"))!!
        assertEquals("serve", parsed.command)
        assertEquals(9000, parsed.port)
        assertTrue(parsed.portSpecified)
    }

    // The control port is the daemon's to choose and publish now (ADR-0059), so naming one is an error
    // rather than an override.
    @Test
    fun rejectsAControlPortFlag() {
        assertNull(parseArgs(arrayOf("serve", "--control-port", "9001")))
    }

    @Test
    fun parsesSearchFilters() {
        val parsed = parseArgs(
            arrayOf(
                "search_traffic",
                "--url", "/login",
                "--method", "POST",
                "--status", "401",
                "--app", "com.example",
            ),
        )!!
        assertEquals("/login", parsed.urlContains)
        assertEquals("POST", parsed.method)
        assertEquals(401, parsed.statusCode)
        assertEquals("com.example", parsed.appId)
    }

    @Test
    fun parsesOnOff() {
        assertEquals(true, parseArgs(arrayOf("set_capturing", "--on"))!!.flag)
        assertEquals(false, parseArgs(arrayOf("set_capturing", "--off"))!!.flag)
    }

    @Test
    fun preservesRepeatedFixtureArguments() {
        val parsed = parseArgs(
            arrayOf(
                "set_map_local",
                "--id", "login",
                "--url-pattern", "https://example.com/path with space/*",
                "--header", "Content-Type: application/json",
                "--header", "X-Test: yes",
                "--body-text", """{"name":"A B"}""",
            ),
        )!!
        assertEquals("https://example.com/path with space/*", parsed.urlPattern)
        assertEquals(listOf("Content-Type: application/json", "X-Test: yes"), parsed.headers)
        assertEquals("""{"name":"A B"}""", parsed.bodyText)
    }

    @Test
    fun rejectsUnknownFlag() {
        assertNull(parseArgs(arrayOf("list_exchanges", "--wat")))
    }

    @Test
    fun emptyArgsIsNull() {
        assertNull(parseArgs(arrayOf()))
    }

    @Test
    fun rejectsInvalidNumericBounds() {
        assertNull(parseArgs(arrayOf("serve", "--port", "0")))
        assertNull(parseArgs(arrayOf("wait_exchange", "--timeout", "-1")))
        assertNull(parseArgs(arrayOf("list_exchanges", "--limit", "0")))
    }

    // A seed is authored exactly like a Map Local rule, so it reuses the same flags rather than growing
    // a parallel set that would drift.
    @Test
    fun aSeedIsAuthoredFromTheSameFlagsAsAMapLocalRule() {
        val parsed = parseArgs(
            arrayOf(
                "set_seed",
                "--id", "poll-1",
                "--url-pattern", "https://example.com/poll",
                "--method", "GET",
                "--status", "202",
                "--header", "Content-Type: application/json",
                "--body-text", """{"state":"pending"}""",
            ),
        )!!
        assertEquals("poll-1", parsed.id)
        assertEquals("https://example.com/poll", parsed.urlPattern)
        assertEquals("GET", parsed.method)
        assertEquals(202, parsed.statusCode)
        assertEquals(listOf("Content-Type: application/json"), parsed.headers)
        assertEquals("""{"state":"pending"}""", parsed.bodyText)
    }

    @Test
    fun commandEnumCoversTier1Surface() {
        val verbs = Command.entries.map { it.verb }.toSet()
        assertTrue(verbs.containsAll(
            listOf(
                "list_exchanges",
                "search_traffic",
                "get_exchange",
                "set_map_local",
                "set_capture_filter",
                "list_devices",
                "list_holds",
                "resume_hold",
                "abort_hold",
                "clear_capture",
                "wait_exchange",
                "set_mcp_access",
                "set_mcp_redaction",
                "set_seed",
                "list_seeds",
                "fill_seeds",
            ),
        ))
    }
}
