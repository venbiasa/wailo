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
        assertNull(parseArgs(arrayOf("set_seed", "--delay-ms", "-1")))
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
                "--delay-ms", "750",
                "--header", "Content-Type: application/json",
                "--body-text", """{"state":"pending"}""",
            ),
        )!!
        assertEquals("poll-1", parsed.id)
        assertEquals("https://example.com/poll", parsed.urlPattern)
        assertEquals("GET", parsed.method)
        assertEquals(202, parsed.statusCode)
        assertEquals(750, parsed.delayMillis)
        assertEquals(listOf("Content-Type: application/json"), parsed.headers)
        assertEquals("""{"state":"pending"}""", parsed.bodyText)
    }

    // Grouping is daemon state now (ADR-0081), so the CLI authors it with the same flags the MCP tools
    // take — the two surfaces have to agree about what a group is or a rule filed from one goes missing
    // from the other.
    @Test
    fun aGroupIsAuthoredAndFiledIntoByIdRatherThanByName() {
        val group = parseArgs(
            arrayOf("set_rule_group", "--family", "map_local", "--group-id", "checkout", "--name", "Checkout", "--off"),
        )!!
        assertEquals("map_local", group.family)
        assertEquals("checkout", group.groupId)
        assertEquals("Checkout", group.name)
        assertEquals(false, group.flag)

        val filed = parseArgs(
            arrayOf("set_map_local", "--id", "login", "--url-pattern", "https://example.com/login", "--group-id", "checkout"),
        )!!
        assertEquals("checkout", filed.groupId)

        val removal = parseArgs(arrayOf("remove_rule_group", "--family", "seeds", "--group-id", "checkout", "--with-rules"))!!
        assertEquals("seeds", removal.family)
        assertTrue(removal.withRules)
        assertEquals(false, parseArgs(arrayOf("remove_rule_group", "--family", "seeds", "--group-id", "checkout"))!!.withRules)
    }

    // A Map Local rule's label is the only human-readable handle on a generated id, and the flag was
    // parsed but dropped on the way to the daemon — so authoring one headlessly lost it silently.
    @Test
    fun aMapLocalRuleIsAuthoredWithItsName() {
        val parsed = parseArgs(
            arrayOf("set_map_local", "--id", "r1", "--url-pattern", "https://example.com/*", "--name", "Login 500"),
        )!!
        assertEquals("Login 500", parsed.name)
    }

    // Naming a phase is presence-only, so "unset" has to stay distinguishable from "off": that is what
    // lets set_breakpoint default to the response without --on-request also stopping it.
    @Test
    fun breakpointPhasesAreUnsetUntilNamed() {
        val bare = parseArgs(arrayOf("set_breakpoint", "--id", "b1", "--url-pattern", "https://example.com/*"))!!
        assertNull(bare.onRequest)
        assertNull(bare.onResponse)

        val request = parseArgs(
            arrayOf("set_breakpoint", "--id", "b1", "--url-pattern", "https://example.com/*", "--on-request"),
        )!!
        assertEquals(true, request.onRequest)
        assertNull(request.onResponse)

        val both = parseArgs(
            arrayOf(
                "set_breakpoint", "--id", "b1", "--url-pattern", "https://example.com/*",
                "--on-request", "--on-response", "--off",
            ),
        )!!
        assertEquals(true, both.onRequest)
        assertEquals(true, both.onResponse)
        assertEquals(false, both.flag)
    }

    // The edited URL cannot ride on --url: every search command already reads that as "contains this", so
    // reusing it would make one command's filter another command's rewrite.
    @Test
    fun aHoldEditNamesItsUrlSeparatelyFromTheSearchFilter() {
        val parsed = parseArgs(
            arrayOf(
                "resume_hold",
                "--id", "c1",
                "--method", "POST",
                "--set-url", "https://example.com/v2/login",
                "--header", "X-Test: yes",
                "--body-text", """{"ok":true}""",
            ),
        )!!
        assertEquals("c1", parsed.id)
        assertEquals("POST", parsed.method)
        assertEquals("https://example.com/v2/login", parsed.editedUrl)
        assertNull(parsed.urlContains)
        assertEquals(listOf("X-Test: yes"), parsed.headers)
    }

    @Test
    fun parsesHoldAndDeviceWaitFilters() {
        val hold = parseArgs(
            arrayOf("wait_hold", "--url-pattern", "https://example.com/*", "--phase", "request", "--timeout", "5"),
        )!!
        assertEquals("request", hold.phase)
        assertEquals(5, hold.timeoutSeconds)

        val device = parseArgs(arrayOf("wait_device", "--app", "com.example", "--device", "Pixel"))!!
        assertEquals("com.example", device.appId)
        assertEquals("Pixel", device.deviceName)
    }

    // list_holds stays one line per hold unless a body budget is asked for by name, so the flag's default
    // is not enough to know whether the caller wanted bodies.
    @Test
    fun aBodyBudgetIsDistinguishableFromItsDefault() {
        assertEquals(false, parseArgs(arrayOf("list_holds"))!!.bodyCharsSpecified)
        val asked = parseArgs(arrayOf("list_holds", "--body-chars", "64"))!!
        assertTrue(asked.bodyCharsSpecified)
        assertEquals(64, asked.bodyChars)
    }

    // Each Capture Filter list has its own switch (ADR-0082). Unset has to stay distinct from off, or
    // every existing invocation would start disarming lists it only meant to leave alone.
    @Test
    fun captureFilterListSwitchesAreUnsetUntilNamed() {
        val derived = parseArgs(arrayOf("set_capture_filter", "--allow", "example.com"))!!
        assertNull(derived.allowlistEnabled)
        assertNull(derived.blocklistEnabled)

        val disarmed = parseArgs(arrayOf("set_capture_filter", "--allow-off", "--block-on"))!!
        assertEquals(false, disarmed.allowlistEnabled)
        assertEquals(true, disarmed.blocklistEnabled)
        assertTrue(disarmed.allowPatterns.isEmpty())
    }

    // Retention is a count of exchanges, not the list limit --limit already means.
    @Test
    fun retentionIsItsOwnCount() {
        assertEquals(5_000, parseArgs(arrayOf("set_max_retained", "--count", "5000"))!!.count)
        assertNull(parseArgs(arrayOf("set_max_retained"))!!.count)
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
                "get_map_local",
                "get_seed",
                "set_breakpoint",
                "list_breakpoints",
                "set_max_retained",
                "wait_device",
                "list_paired",
                "set_rule_order",
                "proxy_setup_guide",
                "proxy_targets",
                "setup_proxy_target",
                "clear_proxy_target",
            ),
        ))
    }

    // An order is a list, and a shell is as likely to build it by repeating the flag as by joining it.
    @Test
    fun anOrderIsAcceptedCommaSeparatedOrRepeated() {
        assertEquals(
            listOf("a", "b", "c"),
            parseArgs(arrayOf("set_rule_order", "--family", "map_local", "--ids", "a,b,c"))!!.ids,
        )
        assertEquals(
            listOf("a", "b"),
            parseArgs(arrayOf("set_rule_order", "--family", "seeds", "--ids", "a", "--ids", "b"))!!.ids,
        )
        assertTrue(parseArgs(arrayOf("set_rule_order", "--family", "map_local"))!!.ids.isEmpty())
    }
}
