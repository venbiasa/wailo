package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CaptureSource
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.hostWildcardMatches
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.proxy.ProxyBodyRef
import com.venbiasa.wailo.proxy.ProxyBodySink
import com.venbiasa.wailo.proxy.ProxyCaptureSink
import com.venbiasa.wailo.proxy.ProxyServer
import com.venbiasa.wailo.proxy.ProxyTls
import java.io.Closeable
import javax.net.ssl.SSLContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What every frontend is shown about the proxy, whether or not a window is open (ADR-0066). */
data class ProxyStatus(
    val running: Boolean = false,
    val port: Int = DEFAULT_PROXY_PORT,
    val connections: Int = 0,
    val exchanges: Long = 0,
    /** Why the last start attempt failed, most often a port something else already holds. */
    val error: String? = null,
    /** Whether a local root exists at all — the first of the two things decryption needs (ADR-0071). */
    val caInstalled: Boolean = false,
    val caFingerprint: String = "",
    val caExpiresEpochMs: Long = 0,
    /** Host patterns the user unlocked. Everything else stays an opaque tunnel. */
    val decryptHosts: List<String> = emptyList(),
)

const val DEFAULT_PROXY_PORT = 9090

/**
 * The daemon's half of the bundled proxy (ADR-0070): owns the listener's lifecycle and translates what
 * it relays into engine rows.
 *
 * The translation is the whole reason `:proxy` can stay off `engine`. It hands over a protobuf plus body
 * handles; this maps those onto [CapturedExchange] with [CaptureSource.PROXY], and maps the engine's
 * [com.venbiasa.wailo.engine.BodySink] back the other way so bytes go straight to the same encrypted
 * spool an SDK capture uses (ADR-0069).
 */
internal class ProxyController(
    private val host: HeadlessHost,
    initialPort: Int = DEFAULT_PROXY_PORT,
    initialDecryptHosts: List<String> = emptyList(),
    private val ca: WailoCertificateAuthority = WailoCertificateAuthority(
        if (KeychainCertificateAuthorityStore.isSupported) {
            KeychainCertificateAuthorityStore()
        } else {
            EphemeralCertificateAuthorityStore()
        },
    ),
    /** Trust used when dialling an origin, for reaching one behind a private root. Default is the JDK's. */
    private val upstream: SSLContext? = null,
) : Closeable {
    private val engine: WailoEngine get() = host.engine
    private val rules = HostProxyRules(host)

    @Volatile
    private var decryptHosts: List<String> = initialDecryptHosts

    /**
     * Two independent gates, deliberately: a root that exists does not decrypt anything, and a host on
     * the list is not decrypted without one (ADR-0071). Generating the root here rather than at start-up
     * keeps a proxy that only tunnels from ever minting a signing key.
     */
    private val tls = object : ProxyTls {
        override fun unlock(host: String): SSLContext? =
            if (decryptHosts.none { hostWildcardMatches(it, host) }) null else ca.contextFor(host)

        override fun upstream(): SSLContext = this@ProxyController.upstream ?: SSLContext.getDefault()
    }

    private val _status = MutableStateFlow(ProxyStatus(port = initialPort, decryptHosts = initialDecryptHosts))
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    @Volatile
    private var server: ProxyServer? = null

    /** Whether the proxy is holding the daemon up. A client pointed at a dead proxy loses its network. */
    val running: Boolean get() = server != null

    @Synchronized
    fun start(port: Int = _status.value.port): Boolean {
        stop()
        return try {
            val started = ProxyServer.start(port, EngineProxyCaptureSink(engine), rules, tls)
            server = started
            _status.value = describe(running = true, port = started.port)
            true
        } catch (failure: Exception) {
            _status.value = describe(
                running = false,
                port = port,
                error = failure.message ?: "could not bind port $port",
            )
            false
        }
    }

    /** Mint the local root if there is not one yet, and hand back what a user needs to install it. */
    fun certificate(): CertificateAuthorityInfo? = ca.ensure().also { publish() }

    fun rotateCertificate(): CertificateAuthorityInfo? = ca.rotate().also { publish() }

    fun removeCertificate() {
        ca.remove()
        publish()
    }

    /**
     * Replace the set of hosts whose TLS is terminated. Existing tunnels are unaffected — a host is
     * decrypted from its next `CONNECT`, since the current one is already an established session.
     */
    fun setDecryptHosts(hosts: List<String>) {
        decryptHosts = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        publish()
    }

    private fun publish() {
        _status.value = describe(running = running, port = _status.value.port, error = _status.value.error)
    }

    private fun describe(running: Boolean, port: Int, error: String? = null): ProxyStatus {
        // Read rather than mint: asking for the status must not be what creates a signing key.
        val root = ca.current()
        return ProxyStatus(
            running = running,
            port = port,
            error = error,
            caInstalled = root != null,
            caFingerprint = root?.sha256.orEmpty(),
            caExpiresEpochMs = root?.notAfterEpochMs ?: 0,
            decryptHosts = decryptHosts,
        )
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        // Holds first: a client parked on a breakpoint has no other route to its origin, so an orderly
        // stop that just closed the listener would leave it waiting on a decision nothing can make.
        rules.releaseAll()
        runCatching { current.close() }
        _status.value = _status.value.copy(running = false, connections = 0, error = null)
    }

    /** Refresh the counters the panel and the menu bar read; they only change as traffic flows. */
    fun sample(): ProxyStatus {
        val current = server ?: return _status.value
        val next = describe(running = true, port = current.port).copy(
            connections = current.connections,
            exchanges = current.exchangeCount,
        )
        _status.value = next
        return next
    }

    @Synchronized
    fun setPort(port: Int): Boolean {
        require(port in 1..65535) { "Proxy port must be between 1 and 65535" }
        if (!running) {
            _status.value = _status.value.copy(port = port, error = null)
            return true
        }
        return start(port)
    }

    override fun close() = stop()
}

private class EngineProxyCaptureSink(private val engine: WailoEngine) : ProxyCaptureSink {
    override fun openBody(): ProxyBodySink {
        val sink = engine.openBodySink()
        return object : ProxyBodySink {
            override fun write(chunk: ByteArray, offset: Int, length: Int) = sink.write(chunk, offset, length)
            override fun commit(): ProxyBodyRef? = sink.commit()?.let { ProxyBodyRef(it.id, it.size) }
            override fun close() = sink.close()
        }
    }

    override fun isRecording(): Boolean = engine.capturing.value

    override fun record(
        exchange: HttpExchange,
        client: String,
        requestBody: ProxyBodyRef?,
        responseBody: ProxyBodyRef?,
    ) {
        engine.record(
            CapturedExchange(
                // A proxy client has no `Hello`, so there is no app or device to name — only the socket
                // it came from. Naming it honestly beats inventing a device the Devices panel would show.
                deviceName = "Proxy",
                appId = client,
                platform = "proxy",
                exchange = exchange,
                requestBody = requestBody?.let { BodyRef(it.id, it.size) },
                responseBody = responseBody?.let { BodyRef(it.id, it.size) },
                source = CaptureSource.PROXY,
            ),
        )
    }
}
