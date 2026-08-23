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
import com.venbiasa.wailo.proxy.ProxyChain
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
    /**
     * Whether the listener is bound beyond loopback (ADR-0074). Reported rather than inferred from
     * [lanAddress], because "a device could reach this" is the thing a surface has to be able to warn
     * about, and it stays true even where the address cannot be resolved.
     */
    val lan: Boolean = false,
    /** This machine on the local network, so a phone can be told where to point. Empty when unknown. */
    val lanAddress: String = "",
    /** Whether a local root exists at all — the first of the two things decryption needs (ADR-0071). */
    val caInstalled: Boolean = false,
    val caFingerprint: String = "",
    val caExpiresEpochMs: Long = 0,
    /** Host patterns the user unlocked. Everything else stays an opaque tunnel. */
    val decryptHosts: List<String> = emptyList(),
    /** Whether this machine's own network settings currently point at Wailo (ADR-0075). */
    val systemProxy: Boolean = false,
    val systemProxySupported: Boolean = false,
    /** The proxy that was already configured here, which Wailo now forwards through. */
    val chainedTo: String = "",
) {
    /** What to actually point a client at — the LAN address only once it is one a client could use. */
    val reachableAddress: String
        get() = "${if (lan && lanAddress.isNotEmpty()) lanAddress else "127.0.0.1"}:$port"
}

/**
 * The local root as a frontend may hold it (ADR-0073): what to show, and the PEM to hand a trust store.
 * There is no field for the private key, and no call that produces one.
 */
data class ProxyCertificate(
    val installed: Boolean,
    val commonName: String = "",
    val sha256: String = "",
    val expiresEpochMs: Long = 0,
    val pem: String = "",
    /** Why there is none, when a caller asked for one — distinct from decryption merely being off. */
    val error: String? = null,
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
    initialLan: Boolean = false,
    /**
     * Defaulted to a session-scoped root rather than the Keychain-backed one: reaching the login
     * Keychain is the daemon entry point's decision to make, so nothing else — a test, a harness — can
     * rotate away the root a user has already trusted.
     */
    private val ca: WailoCertificateAuthority = WailoCertificateAuthority(EphemeralCertificateAuthorityStore()),
    /** Trust used when dialling an origin, for reaching one behind a private root. Default is the JDK's. */
    private val upstream: SSLContext? = null,
    private val system: SystemProxyController = SystemProxyController.forThisMachine(),
) : Closeable {
    private val engine: WailoEngine get() = host.engine
    private val rules = HostProxyRules(host)
    private val chain = ProxyChain(system::upstreamFor)

    // Reads the root, never mints one: a device fetching this page must not be able to create a signing
    // key on the user's machine (ADR-0073/0076).
    private val setup = ProxySetupPage(root = ca::current, decryptHosts = { decryptHosts })

    @Volatile
    private var decryptHosts: List<String> = initialDecryptHosts

    @Volatile
    private var lan: Boolean = initialLan

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

    private val _status = MutableStateFlow(
        ProxyStatus(port = initialPort, decryptHosts = initialDecryptHosts, lan = initialLan),
    )
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    @Volatile
    private var server: ProxyServer? = null

    /** Whether the proxy is holding the daemon up. A client pointed at a dead proxy loses its network. */
    val running: Boolean get() = server != null

    @Synchronized
    fun start(port: Int = _status.value.port): Boolean {
        stopListener()
        return try {
            val started = ProxyServer.start(port, EngineProxyCaptureSink(engine), rules, tls, lan, chain, setup)
            server = started
            // A machine pointed at this port that nobody here took over is a takeover an earlier daemon
            // did not live to undo, and whose record is gone — start-up's recover() would have replayed
            // it otherwise. Sweeping only once the listener is up is the point: the sweep stands down for
            // a port that answers, and from here on the thing answering is us (ADR-0083).
            system.releaseStranded(started.port, listenerIsOurs = true)
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

    /** Why there is no root, when a caller asked for one and did not get it. */
    val certificateError: String? get() = ca.lastError

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
        val machine = system.state()
        return ProxyStatus(
            running = running,
            port = port,
            error = error,
            caInstalled = root != null,
            caFingerprint = root?.sha256.orEmpty(),
            caExpiresEpochMs = root?.notAfterEpochMs ?: 0,
            decryptHosts = decryptHosts,
            lan = lan,
            lanAddress = engine.lanAddress.value.takeUnless { it == "localhost" }.orEmpty(),
            systemProxy = machine.active,
            systemProxySupported = machine.supported,
            chainedTo = machine.chainedTo?.let { "${it.host}:${it.port}" }.orEmpty(),
        )
    }

    /**
     * Point this machine's own network settings at the proxy, or put them back (ADR-0075).
     *
     * Turning it on starts the proxy if it is not already running, because the failure mode of the two
     * being out of step is a machine with no network at all.
     */
    @Synchronized
    fun setSystemProxy(enabled: Boolean): ProxyStatus {
        if (!enabled) {
            val restored = system.restore()
            _status.value = describe(running = running, port = _status.value.port, error = restored.error)
            return _status.value
        }
        if (!running && !start(_status.value.port)) return _status.value
        val applied = system.apply(_status.value.port)
        _status.value = describe(running = running, port = _status.value.port, error = applied.error)
        return _status.value
    }

    /**
     * Bind beyond loopback, or come back to it. Always a restart: a listening socket's address is fixed
     * at bind, so the alternative is a switch that silently does nothing until the next start.
     */
    @Synchronized
    fun setLan(enabled: Boolean): ProxyStatus {
        if (lan == enabled) return _status.value
        lan = enabled
        if (running) start(_status.value.port) else publish()
        return _status.value
    }

    @Synchronized
    fun stop() {
        // Before the listener, and even when there is none: a machine still pointed at a proxy that is
        // no longer there has no network, which is the one failure worse than losing a capture.
        val port = _status.value.port
        system.restore()
        stopListener()
        // Then again from the machine's side, now that the listener is actually gone. A takeover applied
        // by an earlier daemon is invisible to the restore above — that replays a snapshot this process
        // holds — so without this, stopping the proxy is what takes the network down (ADR-0078).
        system.releaseStranded(port)
        _status.value = describe(running = false, port = port).copy(connections = 0)
    }

    /**
     * Close the listener without touching the machine's settings — what a rebind does. A restart must
     * not look like a stop to the system proxy, or changing the port would quietly undo the takeover.
     */
    private fun stopListener() {
        val current = server ?: return
        server = null
        // Holds first: a client parked on a breakpoint has no other route to its origin, so an orderly
        // stop that just closed the listener would leave it waiting on a decision nothing can make.
        rules.releaseAll()
        runCatching { current.close() }
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
        val moved = start(port)
        // A machine pointed at the old port would have no network, so the takeover follows the listener.
        if (moved) system.retarget(_status.value.port)
        return moved
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
