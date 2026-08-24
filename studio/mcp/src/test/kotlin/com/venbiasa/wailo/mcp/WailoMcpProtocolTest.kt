package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WailoMcpProtocolTest {

    @Test
    fun stdioHandshakeListsAndCallsTools() {
        val clientOutput = PipedOutputStream()
        val serverInput = PipedInputStream(clientOutput, PIPE_BUFFER_SIZE)
        val serverOutput = PipedOutputStream()
        val clientInput = PipedInputStream(serverOutput, PIPE_BUFFER_SIZE)
        val reader = clientInput.bufferedReader()
        val host = HeadlessHost.wrap(WailoEngine())
        val server = WailoMcpServer.create(host, 8899, serverInput, serverOutput)
        try {
            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
            )
            val initialized = reader.readLineWithTimeout()
            assertContains(initialized, """"id":1""")
            assertContains(initialized, """"name":"wailo"""")

            clientOutput.sendJson("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            clientOutput.sendJson("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            val tools = reader.readLineWithTimeout()
            assertContains(tools, """"id":2""")
            assertContains(tools, """"name":"list_exchanges"""")
            assertContains(tools, """"name":"set_map_local"""")
            assertContains(tools, """"name":"resume_hold"""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status","arguments":{}}}""",
            )
            val status = reader.readLineWithTimeout()
            assertContains(status, """"id":3""")
            assertContains(status, """"capture_port":8899""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"set_map_local","arguments":{"id":"fixture","url_pattern":"https://example.com/*","method":"GET","status_code":201,"headers":[{"name":"Content-Type","value":"application/json"}],"body_text":"{\"ok\":true}"}}}""",
            )
            val mutation = reader.readLineWithTimeout()
            assertContains(mutation, """"id":4""")
            assertContains(mutation, """"isError":false""")
            assertEquals("fixture", host.engine.rules.value.rules.single().id)
        } finally {
            clientOutput.close()
            server.closeGracefully()
            host.stop()
            reader.close()
        }
    }

    private fun PipedOutputStream.sendJson(json: String) {
        write("$json\n".toByteArray())
        flush()
    }

    private fun BufferedReader.readLineWithTimeout(): String =
        CompletableFuture.supplyAsync { readLine() }.get(5, TimeUnit.SECONDS)

    private companion object {
        const val PIPE_BUFFER_SIZE = 1 shl 20
    }
}
