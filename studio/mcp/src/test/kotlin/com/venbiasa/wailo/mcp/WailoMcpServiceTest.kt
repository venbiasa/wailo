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
                "set_map_local",
                "remove_map_local",
                "list_map_local",
                "set_map_local_enabled",
                "set_capture_filter",
                "list_capture_filter",
                "set_breakpoint",
                "remove_breakpoint",
                "list_breakpoints",
                "set_breakpoints_enabled",
                "resume_hold",
                "abort_hold",
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
                    "methods" to listOf("GET"),
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
    fun parsesMcpProcessOptions() {
        assertEquals(McpConfig(port = 19001, maxRetained = 500), parseMcpArgs(arrayOf("--port", "19001", "--max-retained", "500")))
    }
}
