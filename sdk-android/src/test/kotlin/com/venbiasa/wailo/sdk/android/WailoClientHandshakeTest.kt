package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.RuleSet
import com.venbiasa.wailo.protocol.SealedFrame
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole WiFi path end to end: a real [WailoClient] against a WebSocket server running [FakeStudio],
 * over a socket, with the handshake forced on so loopback is treated as LAN.
 *
 * That forcing is the point. The only peer a unit test can dial is this machine, and loopback is exactly
 * the case the handshake is skipped for — so on iOS the entire guarded path was left to manual smoke,
 * which is how a reconnect that failed *every single time* after a trust-on-first-use pairing shipped
 * unnoticed (ADR-0046).
 */
class WailoClientHandshakeTest {

    private val studio = FakeStudio()

    @Before
    fun setUp() {
        WailoPairingStore.storage = InMemoryKeyValueStore()
        WailoHostStore.storage = InMemoryKeyValueStore()
        Wailo.mutableStatus.value = WailoStatus()
        WailoClient.forcesHandshakeForTesting = true
    }

    @After
    fun tearDown() {
        WailoClient.forcesHandshakeForTesting = false
        Wailo.mutableStatus.value = WailoStatus()
        WailoRuleStore.replace(emptyList())
        WailoCaptureFilterStore.reset()
        WailoBreakpointStore.replace(emptyList())
    }

    @Test
    fun handshakesThenSealsEveryFrame() = runBlocking {
        val port = 18980
        val hellos = Channel<Hello>(Channel.UNLIMITED)
        val acks = Channel<Long>(Channel.UNLIMITED)
        val server = studioServer(port) { session, codec, envelope ->
            envelope.hello?.let {
                hellos.trySend(it)
                // Pushed under the session key: from here on an unsealed frame would be an injection.
                session.sendSealed(
                    codec,
                    Envelope(
                        rule_set = RuleSet(
                            rules = listOf(
                                MapLocalRule(id = "r1", enabled = true, url_pattern = "https://api.example.com/*"),
                            ),
                            epoch = 7L,
                        ),
                    ),
                )
            }
            envelope.rule_ack?.let { acks.trySend(it.epoch) }
        }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            val hello = withTimeout(TIMEOUT_MS) { hellos.receive() }
            assertEquals("com.test", hello.app_id)
            assertEquals(DEVICE_ID_LENGTH, studio.lastDeviceId?.length ?: 0)
            assertTrue(studio.lastFirstContact)

            assertEquals(7L, withTimeout(TIMEOUT_MS) { acks.receive() })
            assertEquals("r1", WailoRuleStore.match("https://api.example.com/x", "GET")?.id)

            val status = client.status.value
            assertTrue(status.connected)
            assertEquals("localhost:$port", status.activeAddress)
            assertEquals(1, status.pairings.size)
            assertTrue(status.pairings.single().trustedOnFirstUse)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    /**
     * ADR-0046, as a test. A device trusted on first use has to come back as a *paired* device, because
     * Studio stored a key for it the moment the first handshake succeeded and will demand proof of that
     * key from the very next connection.
     */
    @Test
    fun reconnectsAsAPairedDeviceAfterATrustOnFirstUse() = runBlocking {
        val port = 18981
        val connections = AtomicInteger()
        val firstContacts = Channel<Boolean>(Channel.UNLIMITED)
        val server = studioServer(port) { session, _, envelope ->
            envelope.hello?.let {
                firstContacts.trySend(studio.lastFirstContact)
                // Drop the link the way a desktop restart does, and let the client find its own way back.
                if (connections.incrementAndGet() == 1) session.close()
            }
        }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            assertTrue("the first connection is a first contact", withTimeout(TIMEOUT_MS) { firstContacts.receive() })
            assertFalse(
                "the reconnect must prove the key Studio just stored",
                withTimeout(RECONNECT_TIMEOUT_MS) { firstContacts.receive() },
            )
            assertEquals(2L, studio.lastSessionCounter)
            assertFalse(client.status.value.pairings.single().trustedOnFirstUse)
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    /**
     * A signed refusal stops the loop rather than feeding it. Retrying would reproduce the same wall
     * every two seconds and bury the reason the user needs to see.
     */
    @Test
    fun aRefusalLatchesAndStopsTheRetryLoop() = runBlocking {
        studio.requirePairing = true
        val port = 18982
        val connections = AtomicInteger()
        val server = studioServer(port, onOpen = { connections.incrementAndGet() }) { _, _, _ -> }

        val client = WailoClient(hello = hello(), host = "localhost", port = port).also { it.start() }
        try {
            withTimeout(TIMEOUT_MS) {
                while (client.status.value.refusal == null) delay(50)
            }
            assertNull(client.status.value.pairings.firstOrNull())
            val seen = connections.get()
            // Comfortably longer than the 2s backoff: if the latch leaked, this would climb.
            delay(RECONNECT_TIMEOUT_MS)
            assertEquals(seen, connections.get())
            assertFalse(client.status.value.connected)

            // Clearing it is a human action, and the device tries exactly once more.
            client.retryAfterRefusal()
            withTimeout(TIMEOUT_MS) {
                while (connections.get() == seen) delay(50)
            }
        } finally {
            client.stop()
            server.stop(0, 0)
        }
    }

    @Test
    fun anUnparseableHostIsRefusedRatherThanStored() = runBlocking {
        val client = WailoClient(hello = hello(), port = 18983)
        try {
            assertFalse(client.setHost("10.0.0.2:8080:9000"))
            assertNull(WailoHostStore.host)
            assertTrue(client.setHost("10.0.0.2:9000"))
            assertEquals("10.0.0.2", WailoHostStore.host)
            assertEquals(9000, WailoHostStore.port)
            assertTrue(client.setHost(null))
            assertNull(WailoHostStore.host)
        } finally {
            client.stop()
        }
    }

    /**
     * Forget has to let go of the address too. A pinned address outranks discovery and a first contact
     * at an address the user named is taken at its word, so dropping only the key would re-trust the
     * same desktop two seconds later — a button that appears to do nothing.
     */
    @Test
    fun forgettingADesktopAlsoUnpinsItsAddress() = runBlocking {
        val client = WailoClient(hello = hello(), port = 18984)
        try {
            client.setHost("10.0.0.2")
            WailoPairingStore.save(
                WailoPairing(
                    studioId = "aaaa",
                    deviceKey = ByteArray(32) { 1 },
                    publicKey = ByteArray(65) { 2 },
                    sessionCounter = 1,
                    refused = false,
                    lastHost = "10.0.0.2",
                    trustedOnFirstUse = true,
                ),
            )
            client.forget("aaaa")
            assertNull(WailoHostStore.host)
            assertNull(WailoPairingStore.pairing("aaaa"))
        } finally {
            client.stop()
        }
    }

    // MARK: - fixture

    private fun hello() = Hello(device_name = "test", app_id = "com.test", platform = "android")

    /**
     * A WebSocket server that runs Studio's half of the handshake and then hands every opened frame to
     * [onEnvelope]. Sealing is handled here so a test only ever deals in envelopes.
     */
    private fun studioServer(
        port: Int,
        onOpen: () -> Unit = {},
        onEnvelope: suspend (DefaultWebSocketServerSession, WailoFrameCodec, Envelope) -> Unit,
    ) = embeddedServer(CIO, port = port) {
        install(WebSockets)
        routing {
            webSocket("/") {
                onOpen()
                val handshake = FakeStudioSession(studio)
                var codec: WailoFrameCodec? = null
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    val envelope = Envelope.ADAPTER.decode(frame.readBytes())
                    val sealed = codec
                    if (sealed == null) {
                        val reply = handshake.handle(envelope)
                        // The result that completes the handshake still travels in the clear; the codec
                        // only covers what comes after it.
                        for (out in reply.out) send(Frame.Binary(true, out.encode()))
                        codec = reply.codec
                        if (reply.close) close()
                    } else {
                        val inner = envelope.sealed_frame ?: break
                        onEnvelope(
                            this,
                            sealed,
                            Envelope.ADAPTER.decode(sealed.open(inner.seq, inner.ciphertext.toByteArray())),
                        )
                    }
                }
            }
        }
    }.also { it.start(wait = false) }

    private suspend fun DefaultWebSocketServerSession.sendSealed(codec: WailoFrameCodec, envelope: Envelope) {
        val sealed = codec.seal(envelope.encode())
        send(
            Frame.Binary(
                true,
                Envelope(
                    sealed_frame = SealedFrame(seq = sealed.seq, ciphertext = sealed.ciphertext.toByteString()),
                ).encode(),
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L

        /** Long enough to cover the client's 2-second backoff plus a fresh handshake. */
        const val RECONNECT_TIMEOUT_MS = 8_000L

        /** [WailoHostStore] mints 16 random bytes as hex. */
        const val DEVICE_ID_LENGTH = 32
    }
}
