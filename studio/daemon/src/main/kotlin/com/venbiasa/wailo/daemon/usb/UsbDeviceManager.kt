package com.venbiasa.wailo.daemon.usb

import com.venbiasa.wailo.daemon.UsbConnectionStatus
import com.venbiasa.wailo.daemon.UsbController
import com.venbiasa.wailo.daemon.UsbDeviceInfo
import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.engine.WailoEngine
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Keeps physical iOS devices attached through usbmuxd for the daemon's lifetime. The WebSocket is fed
 * directly into the transport-neutral engine, so every frontend sees the same cable session.
 */
internal class UsbDeviceManager(
    private val engine: WailoEngine,
    devicePort: Int,
    private val client: UsbmuxdClient = UsbmuxdClient(),
) : UsbController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient.newHttpClient()
    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<UsbDeviceInfo>> = _devices
    private val mutableDevicePort = MutableStateFlow(devicePort)
    override val devicePort: StateFlow<Int> = mutableDevicePort
    private val monitor = UsbmuxdDeviceMonitor(client, scope)
    private val connectionJobs = mutableMapOf<String, Attempt>()
    override val supported: Boolean =
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    init {
        if (supported) {
            monitor.start()
            scope.launch {
                combine(monitor.devices, mutableDevicePort, ::Pair).collectLatest { (attached, port) ->
                    reconcile(attached, port)
                }
            }
        }
    }

    override fun setDevicePort(port: Int) {
        mutableDevicePort.value = port
    }

    private fun reconcile(attached: Map<String, UsbmuxDevice>, port: Int) {
        val removed = connectionJobs.keys - attached.keys
        removed.forEach { udid ->
            connectionJobs.remove(udid)?.job?.cancel()
            _devices.update { list -> list.filterNot { it.udid == udid } }
        }
        attached.values.forEach { device ->
            val existing = connectionJobs[device.udid]
            if (existing?.matches(device.handle, port) == true) return@forEach
            existing?.job?.cancel()
            update(device.udid, UsbConnectionStatus.ATTACHED)
            connectionJobs[device.udid] =
                Attempt(device.handle, port, scope.launch { connectLoop(device, port) })
        }
    }

    private suspend fun connectLoop(device: UsbmuxDevice, port: Int) {
        while (currentCoroutineContext().isActive) {
            update(device.udid, UsbConnectionStatus.CONNECTING)
            try {
                val tunnel = runInterruptible { client.connect(device.handle, port) }
                UsbMuxBridge(tunnel).use { bridge ->
                    coroutineScope {
                        val bridgeJob = launch(Dispatchers.IO) { bridge.run() }
                        try {
                            val connection = JavaWebSocketConnection.connect(
                                udid = device.udid,
                                uri = URI.create("ws://127.0.0.1:${bridge.port}/"),
                                client = httpClient,
                            )
                            update(device.udid, UsbConnectionStatus.CONNECTED)
                            engine.attach(connection)
                        } finally {
                            bridge.close()
                            bridgeJob.cancelAndJoin()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UsbmuxException) {
                val waiting = error.resultCode == RESULT_CONNECTION_REFUSED
                update(
                    device.udid,
                    if (waiting) UsbConnectionStatus.WAITING_FOR_APP else UsbConnectionStatus.ERROR,
                    if (waiting) null else error.message,
                )
            } catch (error: Throwable) {
                update(device.udid, UsbConnectionStatus.ERROR, error.message)
            }
            delay(RETRY_DELAY_MS)
        }
    }

    private fun update(udid: String, status: UsbConnectionStatus, error: String? = null) {
        _devices.update { list ->
            (list.filterNot { it.udid == udid } + UsbDeviceInfo(udid, status, error))
                .sortedBy { it.udid }
        }
    }

    override fun close() {
        connectionJobs.values.forEach { it.job.cancel() }
        connectionJobs.clear()
        monitor.close()
        scope.cancel()
    }

    private data class Attempt(val handle: Int, val port: Int, val job: Job) {
        fun matches(handle: Int, port: Int) =
            this.handle == handle && this.port == port && job.isActive
    }

    private companion object {
        const val RESULT_CONNECTION_REFUSED = 3
        const val RETRY_DELAY_MS = 2000L
    }
}

private class UsbmuxdDeviceMonitor(
    private val client: UsbmuxdClient,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val _devices = MutableStateFlow<Map<String, UsbmuxDevice>>(emptyMap())
    val devices: StateFlow<Map<String, UsbmuxDevice>> = _devices
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { listenLoop() }
            launch { pollLoop() }
        }
    }

    private suspend fun listenLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                runInterruptible {
                    client.listen { event ->
                        when (event) {
                            is UsbmuxEvent.Attached ->
                                _devices.update { it + (event.device.udid to event.device) }
                            is UsbmuxEvent.Detached ->
                                _devices.update { map -> map.filterValues { it.handle != event.handle } }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _devices.value = emptyMap()
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    /**
     * Repairs missed attach/detach events after sleep by periodically asking usbmuxd for its authoritative
     * device set.
     */
    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            val listed = try {
                runInterruptible { client.listDevices() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                continue
            }
            _devices.value = listed.associateBy(UsbmuxDevice::udid)
        }
    }

    override fun close() {
        job?.cancel()
        job = null
        _devices.value = emptyMap()
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 1000L
        const val POLL_INTERVAL_MS = 5000L
    }
}

private class JavaWebSocketConnection private constructor(
    private val udid: String,
) : DeviceConnection, WebSocket.Listener {
    override val id: String = "usb:$udid"
    override val transport: DeviceTransport = DeviceTransport.USB
    override val isTrusted: Boolean = true
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val fragments = mutableListOf<ByteArray>()
    @Volatile
    private var socket: WebSocket? = null

    override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

    override suspend fun send(bytes: ByteArray) {
        val current = socket ?: throw IllegalStateException("USB WebSocket is not open")
        runInterruptible(Dispatchers.IO) {
            current.sendBinary(ByteBuffer.wrap(bytes), true).get()
        }
    }

    override fun close() {
        socket?.abort()
        incoming.close()
    }

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        webSocket.request(1)
    }

    override fun onBinary(
        webSocket: WebSocket,
        data: ByteBuffer,
        last: Boolean,
    ): CompletionStage<*> {
        val bytes = ByteArray(data.remaining()).also(data::get)
        synchronized(fragments) {
            fragments += bytes
            if (last) {
                val size = fragments.sumOf(ByteArray::size)
                val message = ByteArray(size)
                var offset = 0
                fragments.forEach { part ->
                    part.copyInto(message, destinationOffset = offset)
                    offset += part.size
                }
                fragments.clear()
                incoming.trySend(message)
            }
        }
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        webSocket.request(1)
        return webSocket.sendPong(message)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletionStage<*> {
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletionStage<*> {
        incoming.close()
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        incoming.close(error)
    }

    companion object {
        suspend fun connect(
            udid: String,
            uri: URI,
            client: HttpClient,
        ): JavaWebSocketConnection {
            val connection = JavaWebSocketConnection(udid)
            val socket = runInterruptible(Dispatchers.IO) {
                client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, connection)
                    .get()
            }
            connection.socket = socket
            return connection
        }
    }
}

internal class UsbMuxBridge(
    private val tunnel: SocketChannel,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean()
    private var local: Socket? = null
    val port: Int get() = server.localPort

    suspend fun run() = coroutineScope {
        local = runInterruptible(Dispatchers.IO) { server.accept() }
        val socket = local ?: return@coroutineScope
        val tunnelInput = Channels.newInputStream(tunnel)
        val tunnelOutput = Channels.newOutputStream(tunnel)
        val upstream = launch(Dispatchers.IO) {
            runCatching { socket.getInputStream().copyTo(tunnelOutput) }
            close()
        }
        val downstream = launch(Dispatchers.IO) {
            runCatching { tunnelInput.copyTo(socket.getOutputStream()) }
            close()
        }
        joinAll(upstream, downstream)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { local?.close() }
        runCatching { tunnel.close() }
    }
}
