package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.Hello
import com.venbiasa.wailo.protocol.HttpExchange
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One captured exchange plus the identity of the session that produced it. */
data class CapturedExchange(
    val deviceName: String,
    val appId: String,
    val platform: String,
    val exchange: HttpExchange,
)

/**
 * Headless capture server: accepts device WebSocket connections, decodes the
 * protobuf stream, and publishes exchanges as a [StateFlow] for any frontend
 * (desktop UI now; CLI/MCP later). UI-agnostic by design — no Compose here.
 */
class WailoEngine(
    private val port: Int = DEFAULT_PORT,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
) {
    private val _exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val exchanges: StateFlow<List<CapturedExchange>> = _exchanges.asStateFlow()

    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    var hello: Hello? = null
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val envelope = Envelope.ADAPTER.decode(frame.readBytes())
                        envelope.hello?.let { hello = it }
                        envelope.exchange?.let { record(hello, it) }
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }

    private fun record(hello: Hello?, exchange: HttpExchange) {
        val row = CapturedExchange(
            deviceName = hello?.device_name ?: "unknown",
            appId = hello?.app_id ?: "unknown",
            platform = hello?.platform ?: "unknown",
            exchange = exchange,
        )
        _exchanges.update { (it + row).takeLast(maxRetained) }
    }

    companion object {
        const val DEFAULT_PORT: Int = 8899
        private const val DEFAULT_MAX_RETAINED: Int = 1000
        private const val STOP_GRACE_MS: Long = 500L
        private const val STOP_TIMEOUT_MS: Long = 1000L
    }
}
