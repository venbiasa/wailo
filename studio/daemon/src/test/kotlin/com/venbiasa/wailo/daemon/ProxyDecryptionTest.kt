package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTPS through the bundled proxy, both ways round (ADR-0071/0073). The origin here is a real TLS server
 * and the client a real TLS client, because the thing being tested is a handshake: a fake would prove
 * only that the code was called.
 */
class ProxyDecryptionTest {
    private val closeables = mutableListOf<Closeable>()
    private val engine = WailoEngine(port = freePort())
    private val host = HeadlessHost.wrap(engine)

    // Its own ephemeral root: a test must never mint or read the developer's real Keychain entry.
    private val ca = WailoCertificateAuthority(EphemeralCertificateAuthorityStore())
    private val proxy = ProxyController(host, 0, ca = ca, upstream = TestPki.clientContext)
        .also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.reversed().forEach { runCatching(it::close) }
        host.stop()
    }

    @Test
    fun anUnlockedHostIsDecryptedAndItsRequestRecorded() {
        val origin = tlsOrigin { request, out ->
            out.respond("secret ${request.first().substringAfter(' ').substringBefore(' ')}")
        }
        proxy.setDecryptHosts(listOf("localhost"))
        val root = assertNotNull(proxy.certificate(), "a local root must be generated on demand")
        assertTrue(root.sha256.isNotEmpty())
        assertTrue(proxy.start(0))
        host.setCapturing(true)

        val body = through(origin.port, "/vault")

        assertEquals("secret /vault", body)
        val row = awaitRow()
        assertEquals("https://localhost:${origin.port}/vault", row.exchange.request?.url)
        assertEquals(200, row.exchange.response?.code)
    }

    @Test
    fun aLockedHostStaysAnOpaqueTunnel() {
        val origin = tlsOrigin { _, out -> out.respond("still private") }
        assertTrue(proxy.start(0))
        host.setCapturing(true)

        val body = through(origin.port, "/vault")

        assertEquals("still private", body, "a locked host must still work end to end")
        val row = awaitRow()
        assertEquals("CONNECT", row.exchange.request?.method)
        assertTrue(
            row.exchange.response?.message?.contains("not decrypted") == true,
            "a locked tunnel must say so rather than look uncaptured",
        )
    }

    @Test
    fun rotatingTheRootReplacesTheFingerprint() {
        val first = assertNotNull(proxy.certificate())
        val second = assertNotNull(proxy.rotateCertificate())

        assertTrue(first.sha256 != second.sha256, "a rotation that kept the key would not be one")
        proxy.removeCertificate()
        assertEquals(false, proxy.status.value.caInstalled)
    }

    @Test
    fun aStoppedProxyReportsARootThatAlreadyExists() {
        // The bug this guards: the published status seeded `caInstalled` false and only `describe()` ever
        // filled it in, which the listener-off poll skipped — so a fresh daemon told every surface there
        // was no root, and Studio offered to create the one already in the store.
        val store = EphemeralCertificateAuthorityStore()
        val existing = assertNotNull(WailoCertificateAuthority(store).ensure())
        val restarted = ProxyController(host, 0, ca = WailoCertificateAuthority(store))
            .also { closeables += it }

        val status = restarted.sample()

        assertEquals(false, status.running, "the listener stays off — that is the path under test")
        assertTrue(status.caInstalled)
        assertEquals(existing.sha256, status.caFingerprint)
    }

    /** Drive one HTTPS request through the proxy, trusting Wailo's root the way an unlocked client would. */
    private fun through(originPort: Int, path: String): String {
        // Both roots: Wailo's when it decrypts, the origin's own when it does not.
        val trust = trustOf(
            mapOf(
                "wailo" to certificateOf(ca.ensure()!!),
                "origin" to TestPki.certificate,
            ),
        )
        val context = SSLContext.getInstance("TLS").apply { init(null, trust, SecureRandom()) }
        return Socket().use { raw ->
            raw.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.status.value.port), 2_000)
            raw.soTimeout = 10_000
            raw.getOutputStream().apply {
                write("CONNECT localhost:$originPort HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            assertEquals("200", readHeadStatus(raw.getInputStream()))
            val secured = (context.socketFactory as SSLSocketFactory)
                .createSocket(raw, "localhost", originPort, false) as SSLSocket
            secured.startHandshake()
            secured.outputStream.apply {
                write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            String(secured.inputStream.readBytes()).substringAfter("\r\n\r\n")
        }
    }

    private fun awaitRow() = generateSequence { engine.exchanges.value.firstOrNull() ?: Thread.sleep(50).let { null } }
        .take(100)
        .firstOrNull()
        ?: error("no exchange was recorded")

    private fun tlsOrigin(handle: (List<String>, OutputStream) -> Unit): TestOrigin {
        val server = TestPki.serverContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val origin = TestOrigin(server)
        closeables += origin
        thread(isDaemon = true, name = "tls-origin") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    client.use {
                        runCatching { handle(it.getInputStream().readRequestHead(), it.getOutputStream()) }
                    }
                }
            }
        }
        return origin
    }
}

/** A throwaway root that signs `localhost`, standing in for a real origin's own certificate. */
private object TestPki {
    private val authority = WailoCertificateAuthority(EphemeralCertificateAuthorityStore())

    val certificate: X509Certificate =
        certificateOf(authority.ensure() ?: error("could not mint a test root: ${authority.lastError}"))

    val serverContext: SSLContext = authority.contextFor("localhost")
        ?: error("could not sign a test leaf: ${authority.lastError}")

    /** What Wailo dials the origin with, since this root is in nobody's system trust store. */
    val clientContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustOf(mapOf("origin" to certificate)), SecureRandom())
    }
}

private fun trustOf(certificates: Map<String, X509Certificate>) =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEach { (alias, certificate) -> setCertificateEntry(alias, certificate) }
            },
        )
    }.trustManagers

private fun certificateOf(info: CertificateAuthorityInfo): X509Certificate =
    java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(info.pem.byteInputStream()) as X509Certificate

private fun readHeadStatus(input: InputStream): String? {
    val first = input.readLineOrNull() ?: return null
    input.readRequestHead()
    return first.split(' ').getOrNull(1)
}
