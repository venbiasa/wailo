package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WailoMcpServiceTest {

    @Test
    fun fullControlSurfaceHasStableUniqueNames() {
        val names = WailoMcpTools.definitions.map { it.name }

        assertEquals(names.size, names.toSet().size)
        assertEquals(
            setOf(
                "status",
                "list_exchanges",
                "search_traffic",
                "get_exchange",
                "wait_for_exchange",
                "list_devices",
                "wait_for_device",
                "list_holds",
                "wait_for_hold",
                "clear_capture",
                "set_capturing",
                "set_max_retained",
                "proxy_status",
                "get_proxy_setup_guide",
                "list_proxy_targets",
                "set_proxy",
                "set_proxy_port",
                "set_proxy_lan",
                "set_system_proxy",
                "set_proxy_decrypt_hosts",
                "get_proxy_ca",
                "ensure_proxy_ca",
                "rotate_proxy_ca",
                "remove_proxy_ca",
                "setup_proxy_target",
                "clear_proxy_target",
                "set_map_local",
                "remove_map_local",
                "list_map_local",
                "get_map_local",
                "set_map_local_enabled",
                "set_capture_filter",
                "set_capture_filter_enabled",
                "list_capture_filter",
                "set_breakpoint",
                "remove_breakpoint",
                "list_breakpoints",
                "set_breakpoints_enabled",
                "set_seed",
                "remove_seed",
                "list_seeds",
                "get_seed",
                "set_seeds_enabled",
                "fill_seeds",
                "clear_seed_queue",
                "resume_hold",
                "abort_hold",
                "set_rule_group",
                "remove_rule_group",
                "list_rule_groups",
                "set_rule_order",
            ),
            names.toSet(),
        )
    }

    @Test
    fun rulesAndFiltersMutateTheWrappedHost() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val service = WailoMcpService(host, 8899)
        try {
            val mapLocal = service.call(
                "set_map_local",
                mapOf(
                    "id" to "fixture",
                    "url_pattern" to "https://example.com/*",
                    "method" to "GET",
                    "status_code" to 201,
                    "body_text" to """{"ok":true}""",
                ),
            )
            assertFalse(mapLocal.isError)
            assertEquals("fixture", engine.rules.value.rules.single().id)
            assertEquals(201, engine.bodyProvider?.serve("fixture", "https://example.com/a", "GET")?.code)

            val filter = service.call(
                "set_capture_filter",
                mapOf(
                    "allow_patterns" to listOf("*.example.com"),
                    "block_patterns" to listOf("analytics.example.com"),
                ),
            )
            assertFalse(filter.isError)
            assertEquals(listOf("*.example.com"), engine.captureFilter.value.allow_patterns)
            assertEquals(listOf("analytics.example.com"), engine.captureFilter.value.block_patterns)

            val breakpoint = service.call(
                "set_breakpoint",
                mapOf(
                    "id" to "pause",
                    "url_pattern" to "https://example.com/*",
                    "on_request" to true,
                    "on_response" to false,
                ),
            )
            assertFalse(breakpoint.isError)
            assertTrue(engine.breakpointRules.value.rules.single().on_request)
        } finally {
            host.stop()
        }
    }

    @Test
    fun theCaptureFilterMasterPausesDevicesWithoutForgettingWhatWasArmed() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val service = WailoMcpService(host, 8899)
        try {
            service.call("set_capture_filter", mapOf("block_patterns" to listOf("analytics.example.com")))
            assertTrue(engine.captureFilter.value.blocklist_enabled)

            assertFalse(service.call("set_capture_filter_enabled", mapOf("enabled" to false)).isError)
            // Devices capture everything again...
            assertFalse(engine.captureFilter.value.blocklist_enabled)
            // ...but the list is still armed, and says so, which is what makes the master reversible.
            val paused = service.call("list_capture_filter", emptyMap()).data
            assertEquals(false, paused["enabled"])
            assertEquals(true, paused["blocklist_enabled"])
            assertEquals(listOf("analytics.example.com"), paused["block_patterns"])

            service.call("set_capture_filter_enabled", mapOf("enabled" to true))
            assertTrue(engine.captureFilter.value.blocklist_enabled)
            assertEquals(listOf("analytics.example.com"), engine.captureFilter.value.block_patterns)
        } finally {
            host.stop()
        }
    }

    // A list can be populated and disarmed — the state Studio has always been able to author. Reaching it
    // must not cost the patterns, so naming only the switch leaves the list it applies to alone.
    @Test
    fun aListCanBeDisarmedWithoutResendingIt() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val service = WailoMcpService(host, 8899)
        try {
            service.call("set_capture_filter", mapOf("allow_patterns" to listOf("api.example.com")))
            assertTrue(engine.captureFilter.value.allowlist_enabled)

            val disarmed = service.call("set_capture_filter", mapOf("allowlist_enabled" to false))
            assertFalse(disarmed.isError)
            assertFalse(engine.captureFilter.value.allowlist_enabled)
            assertEquals(listOf("api.example.com"), engine.captureFilter.value.allow_patterns)

            service.call("set_capture_filter", mapOf("allowlist_enabled" to true))
            assertTrue(engine.captureFilter.value.allowlist_enabled)
            assertEquals(listOf("api.example.com"), engine.captureFilter.value.allow_patterns)
        } finally {
            host.stop()
        }
    }

    // The old shape has to keep meaning what it did: patterns for one list and nothing for the other still
    // empties the other, so a script written against the derived behaviour does not change under it.
    @Test
    fun omittingAListStillEmptiesIt() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val service = WailoMcpService(host, 8899)
        try {
            service.call("set_capture_filter", mapOf("allow_patterns" to listOf("api.example.com")))
            service.call("set_capture_filter", mapOf("block_patterns" to listOf("ads.example.com")))

            assertTrue(engine.captureFilter.value.allow_patterns.isEmpty())
            assertFalse(engine.captureFilter.value.allowlist_enabled)
            assertEquals(listOf("ads.example.com"), engine.captureFilter.value.block_patterns)
        } finally {
            host.stop()
        }
    }

    @Test
    fun invalidOrStaleMutationsReturnToolErrors() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            assertTrue(
                service.call(
                    "set_map_local",
                    mapOf(
                        "id" to "bad",
                        "url_pattern" to "*",
                        "body_text" to "a",
                        "body_base64" to "Yg==",
                    ),
                ).isError,
            )
            assertTrue(service.call("resume_hold", mapOf("correlation_id" to "missing")).isError)
            assertTrue(service.call("remove_breakpoint", mapOf("id" to "missing")).isError)
        } finally {
            host.stop()
        }
    }

    @Test
    fun onlyGetMapLocalCarriesFixtureBodies() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            service.call(
                "set_map_local",
                mapOf(
                    "id" to "fixture",
                    "name" to "Profile 500",
                    "url_pattern" to "https://example.com/*",
                    "headers" to listOf(mapOf("name" to "Content-Type", "value" to "text/plain")),
                    "body_text" to "hello world",
                ),
            )

            val listed = service.call("list_map_local", emptyMap()).rules().single()
            assertFalse(listed.containsKey("body"))
            assertEquals("Profile 500", listed["name"])
            assertEquals(11, listed["body_bytes"])

            val full = service.call("get_map_local", mapOf("id" to "fixture")).body()
            assertEquals("utf8", full["encoding"])
            assertEquals("hello world", full["data"])
            assertEquals(false, full["output_truncated"])

            val bounded = service.call("get_map_local", mapOf("id" to "fixture", "body_bytes" to 5)).body()
            assertEquals("hello", bounded["data"])
            assertEquals(true, bounded["output_truncated"])

            assertTrue(service.call("get_map_local", mapOf("id" to "missing")).isError)
        } finally {
            host.stop()
        }
    }

    @Test
    fun aSeedIsWrittenDisarmedAndOnlyFillPutsItInPlay() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            assertFalse(
                service.call(
                    "set_seed",
                    mapOf(
                        "id" to "poll-1",
                        "url_pattern" to "https://example.com/poll",
                        "status_code" to 202,
                        "headers" to listOf(mapOf("name" to "Content-Type", "value" to "text/plain")),
                        "body_text" to "pending",
                    ),
                ).isError,
            )
            val authored = service.call("list_seeds", emptyMap()).seeds().single()
            assertFalse(authored.containsKey("body"))
            assertEquals(false, authored["armed"])
            assertEquals(7, authored["body_bytes"])

            assertEquals(1, service.call("fill_seeds", emptyMap()).data["armed"])
            assertEquals(true, service.call("list_seeds", emptyMap()).seeds().single()["armed"])
            assertEquals("pending", service.call("get_seed", mapOf("id" to "poll-1")).seedBody()["data"])

            assertFalse(service.call("clear_seed_queue", emptyMap()).isError)
            assertEquals(false, service.call("list_seeds", emptyMap()).seeds().single()["armed"])
            // Disarming leaves the library alone, so the same sequence can be re-armed.
            assertEquals("poll-1", host.seeds.value.single().id)

            assertTrue(service.call("remove_seed", mapOf("id" to "missing")).isError)
        } finally {
            host.stop()
        }
    }

    /**
     * Grouping is daemon-owned (ADR-0081), so the daemon-less backend has to say no rather than drop the
     * placement — a rule that quietly landed loose would read back as correctly filed on the next list.
     */
    @Test
    fun aDaemonLessMcpRefusesGroupsInsteadOfIgnoringThem() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            val group = service.call("set_rule_group", mapOf("family" to "map_local", "id" to "checkout"))
            assertTrue(group.isError)
            assertTrue(group.text.contains("daemon"))

            val filed = service.call(
                "set_map_local",
                mapOf(
                    "id" to "fixture",
                    "url_pattern" to "https://example.com/*",
                    "group_id" to "checkout",
                ),
            )
            assertTrue(filed.isError)
            assertTrue(host.mapLocalRules.value.isEmpty())

            // A rule with no group named is unaffected: a flat panel is still a working panel.
            assertFalse(
                service.call(
                    "set_map_local",
                    mapOf("id" to "fixture", "url_pattern" to "https://example.com/*"),
                ).isError,
            )
            assertEquals(0, service.call("list_rule_groups", mapOf("family" to "map_local")).groups().size)

            // Order is the same daemon-owned layout, so it is refused for the same reason.
            val ordered = service.call(
                "set_rule_order",
                mapOf("family" to "map_local", "ids" to listOf("fixture")),
            )
            assertTrue(ordered.isError)
            assertTrue(ordered.text.contains("daemon"))
        } finally {
            host.stop()
        }
    }

    /**
     * A rule matches one method (ADR-0087), but a prompt written against the old array has to keep
     * working — and an array is not an override the way a repeated flag is, so the collapse is said out
     * loud rather than left to be discovered on the next list.
     */
    @Test
    fun aLegacyMethodsArrayCollapsesToItsLastEntryAndSaysSo() = runBlocking {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val service = WailoMcpService(host, 8899)
        try {
            val two = service.call(
                "set_map_local",
                mapOf(
                    "id" to "fixture",
                    "url_pattern" to "https://example.com/*",
                    "methods" to listOf("GET", "POST"),
                ),
            )
            assertFalse(two.isError)
            assertEquals("POST", two.data["method"])
            assertTrue(two.text.contains("collapsed to POST"))
            assertEquals("POST", host.mapLocalRules.value.single().method)

            // The scalar wins outright, and one entry collapses silently — there is nothing to report.
            val scalar = service.call(
                "set_map_local",
                mapOf(
                    "id" to "fixture",
                    "url_pattern" to "https://example.com/*",
                    "method" to "PUT",
                    "methods" to listOf("GET"),
                ),
            )
            assertEquals("PUT", scalar.data["method"])
            assertFalse(scalar.text.contains("collapsed"))
        } finally {
            host.stop()
        }
    }

    /** An empty order is a caller mistake, not a request to flatten the panel into its current shape. */
    @Test
    fun anOrderWithoutIdsIsRefused() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            val empty = service.call("set_rule_order", mapOf("family" to "map_local", "ids" to emptyList<String>()))
            assertTrue(empty.isError)
            assertTrue(empty.text.contains("ids"))
        } finally {
            host.stop()
        }
    }

    @Test
    fun anUnknownRuleFamilyIsNamedRatherThanGuessed() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(host, 8899)
        try {
            val failure = service.call("list_rule_groups", mapOf("family" to "map-local"))
            assertTrue(failure.isError)
            assertTrue(failure.text.contains("map_local"))
            assertTrue(service.call("list_rule_groups", emptyMap()).isError)
        } finally {
            host.stop()
        }
    }

    @Test
    fun parsesMcpProcessOptions() {
        assertEquals(McpConfig(port = 19001, maxRetained = 500), parseMcpArgs(arrayOf("--port", "19001", "--max-retained", "500")))
    }
}

@Suppress("UNCHECKED_CAST")
private fun McpToolResponse.groups(): List<Map<String, Any?>> = data["groups"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
private fun McpToolResponse.rules(): List<Map<String, Any?>> = data["rules"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
private fun McpToolResponse.body(): Map<String, Any?> =
    (data["rule"] as Map<String, Any?>)["body"] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun McpToolResponse.seeds(): List<Map<String, Any?>> = data["seeds"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
private fun McpToolResponse.seedBody(): Map<String, Any?> =
    (data["seed"] as Map<String, Any?>)["body"] as Map<String, Any?>
