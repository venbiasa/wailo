package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.ProxyStatus
import com.venbiasa.wailo.daemon.provision.CaTrust
import com.venbiasa.wailo.daemon.provision.ProxyTarget
import com.venbiasa.wailo.daemon.provision.ProxyTargetKind
import com.venbiasa.wailo.daemon.provision.ProxyTargetOutcome
import com.venbiasa.wailo.daemon.provision.ProxyTargetScan
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
        val backend = ProtocolBackend(host)
        val server = WailoMcpServer.create(backend, serverInput, serverOutput)
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
            assertContains(tools, """"name":"proxy_status"""")
            assertContains(tools, """"name":"get_proxy_setup_guide"""")
            assertContains(tools, """"name":"get_proxy_ca"""")
            assertContains(tools, """"name":"setup_proxy_target"""")
            assertContains(tools, """"confirm"""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status","arguments":{}}}""",
            )
            val status = reader.readLineWithTimeout()
            assertContains(status, """"id":3""")
            assertContains(status, """"capture_port":8899""")
            assertContains(status, """"reachable_address":"192.168.1.20:9090"""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_proxy_setup_guide","arguments":{}}}""",
            )
            val guide = reader.readLineWithTimeout()
            assertContains(guide, """"id":4""")
            assertContains(guide, """"setup_url":"http://wailo.test/setup"""")
            assertContains(guide, """"next_steps"""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"setup_proxy_target","arguments":{"id":"phone-1","confirm":true}}}""",
            )
            val target = reader.readLineWithTimeout()
            assertContains(target, """"id":5""")
            assertContains(target, """"proxy_set":true""")
            assertContains(target, """"trust":"user"""")

            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"set_map_local","arguments":{"id":"fixture","url_pattern":"https://example.com/*","method":"GET","status_code":201,"headers":[{"name":"Content-Type","value":"application/json"}],"body_text":"{\"ok\":true}"}}}""",
            )
            val mutation = reader.readLineWithTimeout()
            assertContains(mutation, """"id":6""")
            assertContains(mutation, """"isError":false""")
            assertEquals("fixture", host.engine.rules.value.rules.single().id)

            backend.revoke()
            clientOutput.sendJson(
                """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"status","arguments":{}}}""",
            )
            val revoked = reader.readLineWithTimeout()
            assertContains(revoked, """"id":7""")
            assertContains(revoked, """"isError":true""")
            assertContains(revoked, """set_mcp_access --on""")
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

private class ProtocolBackend(host: HeadlessHost) : McpBackend by LocalMcpBackend(host, 8899) {
    private var allowed = true
    override val mcpAccess: Boolean get() = allowed

    override val proxyStatus = ProxyStatus(
        running = true,
        port = 9090,
        lan = true,
        lanAddress = "192.168.1.20",
    )

    override suspend fun proxyTargets() = ProxyTargetScan(
        supported = true,
        targets = listOf(
            ProxyTarget(
                id = "phone-1",
                name = "Phone",
                kind = ProxyTargetKind.ANDROID_DEVICE,
            ),
        ),
    )

    override suspend fun setUpProxyTarget(id: String) = ProxyTargetOutcome(
        target = proxyTargets().targets.single(),
        proxySet = true,
        trust = CaTrust.USER,
        certificateCurrent = true,
    )

    fun revoke() {
        allowed = false
    }
}
