package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The page a device gets by browsing *to* the proxy port (ADR-0076) — the only way a phone can be handed
 * the local root. Driven over a real socket, because what is being tested is that an origin-form request
 * is answered at all rather than refused as a malformed proxy request.
 */
class ProxySetupPageTest {
    private val closeables = mutableListOf<Closeable>()
    private val engine = WailoEngine(port = freePort())
    private val host = HeadlessHost.wrap(engine)
    private val ca = WailoCertificateAuthority(EphemeralCertificateAuthorityStore())
    private val proxy = ProxyController(host, 0, ca = ca).also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.reversed().forEach { runCatching(it::close) }
        host.stop()
    }

    @Test
    fun theLandingPageDoesNotMintARoot() {
        assertTrue(proxy.start(0))

        val page = fetch("/")

        assertTrue(page.contains("200 OK"), page)
        assertTrue(page.contains("create one"), "with no root the page must say where to make one")
        assertEquals(
            false,
            proxy.status.value.caInstalled,
            "a device asking for the page must never be what puts a signing key on this machine",
        )
    }

    @Test
    fun theCertificateIsServedAsAProfileOnceOneExists() {
        assertNotNull(proxy.certificate())
        proxy.setDecryptHosts(listOf("api.example.com"))
        assertTrue(proxy.start(0))

        val landing = fetch("/")
        val certificate = fetch("/cert")

        assertTrue(landing.contains("api.example.com"), "the page must say what is actually readable")
        assertTrue(certificate.contains("application/x-x509-ca-cert"), certificate.substringBefore("\r\n\r\n"))
        assertTrue(certificate.contains("BEGIN CERTIFICATE"), "the root itself must come back")
        assertTrue(
            !certificate.contains("PRIVATE KEY"),
            "an export is the certificate alone — the key never leaves the store",
        )
    }

    @Test
    fun anUnknownPathIsStillRefusedAsAProxyPort() {
        assertTrue(proxy.start(0))

        val response = fetch("/wp-login.php")

        assertTrue(response.contains("400 Bad Request"), response)
    }

    /** An origin-form GET, which is what a browser sends when it dials this port directly. */
    private fun fetch(path: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.status.value.port), 2_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        String(socket.getInputStream().readBytes())
    }
}
