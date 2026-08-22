package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.CaptureSource
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.proxy.ProxyBodyRef
import com.venbiasa.wailo.proxy.ProxyBodySink
import com.venbiasa.wailo.proxy.ProxyCaptureSink
import com.venbiasa.wailo.proxy.ProxyServer
import java.io.Closeable
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
    private val engine: WailoEngine,
    initialPort: Int = DEFAULT_PROXY_PORT,
) : Closeable {
    private val _status = MutableStateFlow(ProxyStatus(port = initialPort))
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    @Volatile
    private var server: ProxyServer? = null

    /** Whether the proxy is holding the daemon up. A client pointed at a dead proxy loses its network. */
    val running: Boolean get() = server != null

    @Synchronized
    fun start(port: Int = _status.value.port): Boolean {
        stop()
        return try {
            val started = ProxyServer.start(port, EngineProxyCaptureSink(engine))
            server = started
            _status.value = ProxyStatus(running = true, port = started.port)
            true
        } catch (failure: Exception) {
            _status.value = ProxyStatus(
                running = false,
                port = port,
                error = failure.message ?: "could not bind port $port",
            )
            false
        }
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.close() }
        _status.value = _status.value.copy(running = false, connections = 0, error = null)
    }

    /** Refresh the counters the panel and the menu bar read; they only change as traffic flows. */
    fun sample(): ProxyStatus {
        val current = server ?: return _status.value
        val next = _status.value.copy(
            running = true,
            port = current.port,
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
