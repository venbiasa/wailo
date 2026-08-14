package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString

class McpAccessAndRedactionTest {

    @Test
    fun refusedAccessAnswersEveryToolAndNamesHowToRestoreIt() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val service = WailoMcpService(LocalMcpBackend(host, 8899, mcpAccess = false))
        try {
            val status = service.call("status", emptyMap())
            assertTrue(status.isError)
            assertContains(status.text, "set_mcp_access --on")
            // Refusing status too: a gate that leaks the LAN address and exchange counts has not been
            // closed, it has been narrowed.
            assertFalse(status.data.containsKey("lan_address"))

            assertTrue(
                service.call(
                    "set_map_local",
                    mapOf("id" to "blocked", "url_pattern" to "https://example.com/*"),
                ).isError,
            )
            assertTrue(host.mapLocalRules.value.isEmpty())
        } finally {
            host.stop()
        }
    }

    @Test
    fun grantedAccessReportsWhetherItIsRedacting() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        try {
            val redacting = WailoMcpService(LocalMcpBackend(host, 8899, redactSecrets = true))
            assertEquals(true, redacting.call("status", emptyMap()).data["redacting_secrets"])

            val plain = WailoMcpService(LocalMcpBackend(host, 8899, redactSecrets = false))
            assertEquals(false, plain.call("status", emptyMap()).data["redacting_secrets"])
        } finally {
            host.stop()
        }
    }

    @Test
    fun capturedCredentialsNeverReachATool() = runBlocking {
        withCapturedExchange { host: HeadlessHost ->
            val detail = WailoMcpService(LocalMcpBackend(host, 8899, redactSecrets = true))
                .call("get_exchange", mapOf("id" to EXCHANGE_ID))
                .exchange()
            val request = detail["request"].asMap()

            assertEquals(REDACTED_VALUE, request.header("Authorization"))
            assertEquals("session=$REDACTED_VALUE", request.header("Cookie"))
            assertEquals("application/json", request.header("Content-Type"))
            assertEquals(
                "https://example.com/login?access_token=$REDACTED_VALUE&page=2",
                request["url"],
            )

            val body = request["body"].asMap()["data"] as String
            assertFalse(body.contains("hunter2"), "request body leaked a password: $body")
            assertContains(body, REDACTED_VALUE)
            assertContains(body, "alice", message = "redaction should keep the fields that are not secret")

            val response = detail["response"].asMap()
            assertEquals("refresh=$REDACTED_VALUE; Path=/; HttpOnly", response.header("Set-Cookie"))
        }
    }

    @Test
    fun turningRedactionOffHandsBackTheRealValues() = runBlocking {
        withCapturedExchange { host ->
            val request = WailoMcpService(LocalMcpBackend(host, 8899, redactSecrets = false))
                .call("get_exchange", mapOf("id" to EXCHANGE_ID))
                .exchange()["request"]
                .asMap()

            assertEquals("Bearer live-token", request.header("Authorization"))
            assertContains(request["body"].asMap()["data"] as String, "hunter2")
        }
    }

    @Test
    fun redactedHeadersEchoedBackDoNotOverwriteTheRealOnes() {
        val original = listOf(
            Header(name = "Authorization", value_ = "Bearer live-token"),
            Header(name = "Accept", value_ = "application/json"),
        )
        val echoed = listOf(
            Header(name = "Authorization", value_ = REDACTED_VALUE),
            Header(name = "Accept", value_ = "text/plain"),
        )

        val restored = echoed.restoreRedacted(original)

        assertEquals("Bearer live-token", restored.single { it.name == "Authorization" }.value_)
        assertEquals("text/plain", restored.single { it.name == "Accept" }.value_)
    }

    @Test
    fun redactionFollowsTheKeyNotTheFormat() {
        assertEquals(
            """{"user":"alice","auth":{"accessToken":"$REDACTED_VALUE"}}""",
            redactBodyText("""{"user":"alice","auth":{"accessToken":"abc"}}""", "application/json"),
        )
        // A sensitive key hides its whole subtree, or a secret one level down survives under an
        // innocuous name.
        assertEquals(
            """{"credentials":"$REDACTED_VALUE"}""",
            redactBodyText("""{"credentials":{"user":"alice","pin":1234}}""", "application/json"),
        )
        assertEquals(
            "user=alice&password=$REDACTED_VALUE",
            redactBodyText("user=alice&password=hunter2", "application/x-www-form-urlencoded"),
        )
        assertEquals(
            "<user>alice</user><apiKey>$REDACTED_VALUE</apiKey>",
            redactBodyText("<user>alice</user><apiKey>abc</apiKey>", "application/xml"),
        )
        // Truncated JSON cannot be rewritten field by field, so it is withheld rather than passed on.
        assertFalse(redactBodyText("""{"token":"abc""", "application/json").contains("abc"))
        // "author" is not "auth": the heuristic matches whole secret words, not substrings.
        assertEquals("""{"author":"alice"}""", redactBodyText("""{"author":"alice"}""", "application/json"))
    }

    private suspend fun withCapturedExchange(assertions: suspend (HeadlessHost) -> Unit) = coroutineScope {
        val engine = WailoEngine()
        val host = HeadlessHost.wrap(engine)
        val connection = FakeConnection()
        val serving = launch { engine.attach(connection) }
        try {
            connection.incoming.send(Envelope(hello = HELLO).encode())
            connection.incoming.send(Envelope(exchange = CAPTURED).encode())
            withTimeout(5_000) {
                while (host.findExchangeById(EXCHANGE_ID) == null) delay(25)
            }
            assertions(host)
        } finally {
            connection.incoming.close()
            serving.join()
            host.stop()
        }
    }

    private fun McpToolResponse.exchange(): Map<*, *> {
        assertFalse(isError, text)
        return data["exchange"].asMap()
    }

    private fun Any?.asMap(): Map<*, *> = this as Map<*, *>

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.header(name: String): String? =
        (this["headers"] as List<Map<*, *>>).firstOrNull { it["name"] == name }?.get("value") as String?

    private class FakeConnection : DeviceConnection {
        override val id = "usb:redaction"
        override val transport = DeviceTransport.USB
        override val isTrusted = true
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            outgoing.send(bytes)
        }

        override fun close() {
            incoming.close()
            outgoing.close()
        }
    }

    private companion object {
        const val EXCHANGE_ID = "redaction-1"

        val HELLO = Hello(device_name = "test-device", app_id = "com.example", platform = "ios")

        val REQUEST_BODY = """{"user":"alice","password":"hunter2"}"""

        val CAPTURED = HttpExchange(
            id = EXCHANGE_ID,
            request = HttpRequest(
                method = "POST",
                url = "https://example.com/login?access_token=live-token&page=2",
                headers = listOf(
                    Header(name = "Authorization", value_ = "Bearer live-token"),
                    Header(name = "Cookie", value_ = "session=abc123"),
                    Header(name = "Content-Type", value_ = "application/json"),
                ),
                body = REQUEST_BODY.toByteArray().toByteString(),
                body_size = REQUEST_BODY.length.toLong(),
            ),
            response = HttpResponse(
                code = 200,
                headers = listOf(
                    Header(name = "Set-Cookie", value_ = "refresh=xyz789; Path=/; HttpOnly"),
                ),
            ),
        )
    }
}
