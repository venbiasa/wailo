package com.venbiasa.wailo.cli

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ControlChannelTest {

    @Test
    fun oneShotCommandsMutateTheLongLivedHost() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val port = freePort()
        val tokens = MemoryControlTokenStore()
        val server = ControlServer(host, port, tokens).also(ControlServer::start)
        val client = ControlClient(port, tokens)
        try {
            val filter = client.execute(
                arrayOf(
                    "set_capture_filter",
                    "--allow", "*.example.com",
                    "--block", "analytics.example.com",
                ),
                timeoutSeconds = 5,
            )
            assertEquals(0, filter.exitCode)
            assertEquals(listOf("*.example.com"), host.engine.captureFilter.value.allow_patterns)
            assertEquals(listOf("analytics.example.com"), host.engine.captureFilter.value.block_patterns)

            val mapLocal = client.execute(
                arrayOf(
                    "set_map_local",
                    "--id", "login",
                    "--url-pattern", "https://api.example.com/path with space/*",
                    "--status", "201",
                    "--header", "Content-Type: application/json",
                    "--body-text", """{"name":"A B"}""",
                ),
                timeoutSeconds = 5,
            )
            assertEquals(0, mapLocal.exitCode)
            assertEquals("login", host.mapLocalRules.value.single().id)
            val served = host.engine.bodyProvider?.serve(
                "login",
                "https://api.example.com/path with space/1",
                "GET",
            )
            assertEquals(201, served?.code)
            assertContentEquals("""{"name":"A B"}""".toByteArray(), served?.body)

            val unknownHold = client.execute(arrayOf("resume_hold", "--id", "missing"), timeoutSeconds = 5)
            assertEquals(1, unknownHold.exitCode)
            assertTrue(unknownHold.message.contains("not found"))
        } finally {
            server.close()
            host.stop()
        }
    }

    @Test
    fun wrongControlTokenIsRejected() {
        val host = HeadlessHost.wrap(WailoEngine())
        val port = freePort()
        val serverTokens = MemoryControlTokenStore()
        val clientTokens = MemoryControlTokenStore().also { it.write(port, ByteArray(32) { 7 }) }
        val server = ControlServer(host, port, serverTokens).also(ControlServer::start)
        try {
            val result = ControlClient(port, clientTokens).execute(arrayOf("list_exchanges"), timeoutSeconds = 5)
            assertEquals(2, result.exitCode)
            assertTrue(result.message.contains("authentication"))
            assertFalse(host.engine.listening.value)
        } finally {
            server.close()
            host.stop()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
