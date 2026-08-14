package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HeadlessHost
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Bind the control socket on an OS-chosen port and publish it in the handshake file, so the daemon can
 * never fail to start because an unrelated process holds one fixed port (ADR-0059).
 */
internal const val AUTO_PORT = 0

internal const val TOKEN_BYTES = 32

internal val DaemonJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal class DaemonRuntime(
    val host: HeadlessHost,
    private val usb: UsbController,
    private val adb: AdbController,
    private val settings: DaemonSettings,
    private val pairingSupported: Boolean,
    mcpAccess: Boolean = true,
    mcpRedactSecrets: Boolean = true,
) : AutoCloseable {
    // Daemon-owned rather than engine-owned: neither value changes what is captured, only what an MCP
    // client is allowed to do with it, and both must outlive whichever frontend flipped them.
    private val _mcpAccess = MutableStateFlow(mcpAccess)
    private val _mcpRedactSecrets = MutableStateFlow(mcpRedactSecrets)
    val mcpAccess: StateFlow<Boolean> = _mcpAccess.asStateFlow()
    val mcpRedactSecrets: StateFlow<Boolean> = _mcpRedactSecrets.asStateFlow()

    fun poll(request: PollRequest): PollResponse {
        val current = host.listExchanges()
        val lastIndex = request.lastExchangeId?.let { id ->
            current.indexOfFirst { it.exchange.id == id }.takeIf { it >= 0 }
        }
        val reset = when {
            current.isEmpty() -> request.hasExchanges
            request.lastExchangeId == null -> request.hasExchanges
            lastIndex == null -> true
            else -> false
        }
        val exchangeRows = when {
            reset || request.lastExchangeId == null -> current
            else -> current.drop(lastIndex!! + 1)
        }
        val exchangeDtos = exchangeBatch(exchangeRows)

        val holds = host.listHolds()
        val holdsHash = hash(holds.map { it.toDto() })
        val mapRules = host.mapLocalRules.value
        val mapHash = hash(mapRules.map { it.toDto() })
        val breakpointRules = host.breakpointRules.value
        val breakpointHash = hash(breakpointRules.map { it.toDto() })
        val engine = host.engine
        val identity = engine.pairings.identity.value
        val offer = engine.pairings.offer.value
        val address = engine.lanAddress.value
        val port = engine.port
        return PollResponse(
            resetExchanges = reset,
            firstExchangeId = current.firstOrNull()?.exchange?.id,
            exchanges = exchangeDtos,
            exchangesCaughtUp = exchangeDtos.size == exchangeRows.size,
            listening = engine.listening.value,
            capturePort = port,
            lanAddress = address,
            capturing = engine.capturing.value,
            maxRetained = engine.maxRetained.value,
            connectedDevices = engine.connectedDevices.value.map { it.toDto() },
            captureFilterBase64 = engine.captureFilter.value.encodeBase64(),
            holdsHash = holdsHash,
            holds = holds.takeUnless { request.holdsHash == holdsHash }?.map { it.toDto() },
            mapLocalEnabled = host.isMapLocalEnabled(),
            mapLocalHash = mapHash,
            mapLocalRules = mapRules.takeUnless { request.mapLocalHash == mapHash }?.map { it.toDto() },
            breakpointsEnabled = host.areBreakpointsEnabled(),
            breakpointHash = breakpointHash,
            breakpointRules = breakpointRules
                .takeUnless { request.breakpointHash == breakpointHash }
                ?.map { it.toDto() },
            pairing = PairingDto(
                supported = pairingSupported,
                requirePairing = engine.requirePairing.value,
                studioId = identity.studioId,
                offer = offer?.let {
                    PairingOfferDto(
                        code = it.code,
                        expiresAtEpochMs = it.expiresAtEpochMs,
                        qrPayload = it.qrPayload(identity.studioId, identity.publicKey, address, port),
                    )
                },
                devices = engine.pairings.devices.value.map { it.toDto() },
                refusals = engine.refusedDevices.value.map { it.toDto() },
                suspectedClones = engine.suspectedClones.value,
            ),
            mcpAccess = _mcpAccess.value,
            mcpRedactSecrets = _mcpRedactSecrets.value,
            usbSupported = usb.supported,
            usbPort = usb.devicePort.value,
            usbDevices = usb.devices.value.map { UsbDeviceDto(it.udid, it.status.name, it.error) },
            adbSupported = adb.supported,
            adbExecutable = adb.executablePath,
            adbDevices = adb.devices.value.map { AdbDeviceDto(it.serial, it.name, it.status.name, it.error) },
        )
    }

    suspend fun rebind(port: Int): Boolean {
        if (port !in 1..65535) return false
        val rebound = host.rebind(port)
        if (rebound) {
            adb.setHostPort(port)
            settings.update { it.copy(capturePort = port) }
        }
        return rebound
    }

    fun setMaxRetained(value: Int) {
        require(value in 100..100_000) { "maxRetained must be between 100 and 100000" }
        host.setMaxRetained(value)
        settings.update { it.copy(maxRetained = value) }
    }

    fun setUsbPort(value: Int) {
        require(value in 1..65535) { "USB port must be between 1 and 65535" }
        usb.setDevicePort(value)
        settings.update { it.copy(usbPort = value) }
    }

    fun setRequirePairing(value: Boolean) {
        host.engine.setRequirePairing(value)
        settings.update { it.copy(requirePairing = value) }
    }

    fun setMcpAccess(value: Boolean) {
        _mcpAccess.value = value
        settings.update { it.copy(mcpAccess = value) }
    }

    fun setMcpRedactSecrets(value: Boolean) {
        _mcpRedactSecrets.value = value
        settings.update { it.copy(mcpRedactSecrets = value) }
    }

    override fun close() {
        usb.close()
        adb.close()
        host.stop()
    }

    private fun hash(value: Any): String {
        val bytes = DaemonJson.encodeToString(value.toString()).toByteArray()
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun exchangeBatch(rows: List<com.venbiasa.wailo.engine.CapturedExchange>): List<CapturedExchangeDto> {
        var encodedBytes = 0
        return buildList {
            for (row in rows.take(MAX_EXCHANGES_PER_POLL)) {
                val dto = row.toDto()
                val size = dto.exchangeBase64.length + dto.deviceName.length + dto.appId.length + dto.platform.length
                if (isNotEmpty() && encodedBytes + size > MAX_EXCHANGE_BYTES_PER_POLL) break
                add(dto)
                encodedBytes += size
            }
        }
    }

    private companion object {
        const val MAX_EXCHANGES_PER_POLL = 512
        const val MAX_EXCHANGE_BYTES_PER_POLL = 16 * 1024 * 1024
    }
}

internal class DaemonServer(
    private val runtime: DaemonRuntime,
    requestedPort: Int = AUTO_PORT,
    private val handshakeStore: DaemonHandshakeStore = FileDaemonHandshakeStore(),
    private val onStop: () -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)

    /** The port actually bound, which callers need because [AUTO_PORT] lets the OS choose one. */
    val port: Int get() = socket.localPort

    init {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LOOPBACK, requestedPort))
        try {
            handshakeStore.write(
                DaemonHandshake(
                    controlPort = socket.localPort,
                    protocolVersion = DAEMON_CONTROL_PROTOCOL_VERSION,
                    token = token,
                ),
            )
        } catch (failure: Exception) {
            socket.close()
            executor.shutdownNow()
            throw failure
        }
    }

    fun start() {
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            }
            executor.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            val input = DataInputStream(it.getInputStream())
            val output = DataOutputStream(it.getOutputStream())
            val response = try {
                val framed = DaemonWire.readRequest(input)
                if (!MessageDigest.isEqual(token, framed.token)) {
                    RpcResponse(ok = false, error = "Daemon authentication failed")
                } else {
                    runBlocking { dispatch(DaemonJson.decodeFromString(RpcRequest.serializer(), framed.json)) }
                }
            } catch (failure: Throwable) {
                // Throwable, not Exception: a request that dies on an Error (a jar swapped out from under
                // a running daemon during the dev loop is the one that happens) would otherwise kill this
                // thread and close the socket with no reply, leaving every client to report an unexplained
                // readiness timeout. One bad request must still produce an answer the caller can quote.
                RpcResponse(ok = false, error = failure.rpcError())
            }
            DaemonWire.writeResponse(output, DaemonJson.encodeToString(RpcResponse.serializer(), response))
        }
    }

    private suspend fun dispatch(request: RpcRequest): RpcResponse {
        fun success(payload: JsonElement = JsonNull) = RpcResponse(ok = true, payload = payload)
        return when (request.command) {
            "ping" -> success(
                DaemonJson.encodeToJsonElement(IntValue(DAEMON_CONTROL_PROTOCOL_VERSION)),
            )
            "poll" -> success(DaemonJson.encodeToJsonElement(runtime.poll(request.decode(PollRequest.serializer()))))
            "clear" -> {
                runtime.host.clear()
                success()
            }
            "set_capturing" -> {
                runtime.host.setCapturing(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_max_retained" -> {
                runtime.setMaxRetained(request.decode(IntValue.serializer()).value)
                success()
            }
            "rebind" -> success(
                DaemonJson.encodeToJsonElement(BooleanValue(runtime.rebind(request.decode(IntValue.serializer()).value))),
            )
            "restart" -> success(DaemonJson.encodeToJsonElement(BooleanValue(runtime.host.engine.restart())))
            "mcp_settings" -> success(
                DaemonJson.encodeToJsonElement(
                    McpSettingsDto(
                        access = runtime.mcpAccess.value,
                        redactSecrets = runtime.mcpRedactSecrets.value,
                    ),
                ),
            )
            "set_mcp_access" -> {
                runtime.setMcpAccess(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_mcp_redact_secrets" -> {
                runtime.setMcpRedactSecrets(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "set_usb_port" -> {
                runtime.setUsbPort(request.decode(IntValue.serializer()).value)
                success()
            }
            "set_capture_filter" -> {
                val value = request.decode(CaptureFilterRequest.serializer())
                runtime.host.updateCaptureFilter(
                    value.allowlistEnabled,
                    value.allowPatterns,
                    value.blocklistEnabled,
                    value.blockPatterns,
                )
                success()
            }
            "replace_map_local" -> {
                val value = request.decode(ReplaceMapLocalRequest.serializer())
                runtime.host.replaceMapLocalRules(value.rules.map(MapLocalRuleDto::toDomain), value.enabled)
                success()
            }
            "upsert_map_local" -> {
                runtime.host.upsertMapLocalRule(request.decode(MapLocalRuleDto.serializer()).toDomain())
                success()
            }
            "remove_map_local" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.host.removeMapLocalRule(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "set_map_local_enabled" -> {
                runtime.host.setMapLocalEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "replace_breakpoints" -> {
                val value = request.decode(ReplaceBreakpointsRequest.serializer())
                runtime.host.replaceBreakpointRules(value.rules.map(BreakpointRuleDto::toDomain), value.enabled)
                success()
            }
            "upsert_breakpoint" -> {
                runtime.host.upsertBreakpointRule(request.decode(BreakpointRuleDto.serializer()).toDomain())
                success()
            }
            "remove_breakpoint" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.host.removeBreakpointRule(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "set_breakpoints_enabled" -> {
                runtime.host.setBreakpointsEnabled(request.decode(BooleanValue.serializer()).value)
                success()
            }
            "resume_hold" -> {
                val value = request.decode(ResumeHoldRequest.serializer())
                success(
                    DaemonJson.encodeToJsonElement(
                        BooleanValue(
                            runtime.host.resumeHold(
                                value.correlationId,
                                value.editedRequestBase64?.decodeRequest(),
                                value.editedResponseBase64?.decodeResponse(),
                            ),
                        ),
                    ),
                )
            }
            "abort_hold" -> success(
                DaemonJson.encodeToJsonElement(
                    BooleanValue(runtime.host.abortHold(request.decode(IdRequest.serializer()).id)),
                ),
            )
            "pairing" -> {
                val value = request.decode(PairingActionRequest.serializer())
                when (value.action) {
                    "begin" -> runtime.host.engine.pairings.beginPairing()
                    "cancel" -> runtime.host.engine.pairings.cancelPairing()
                    "forget" -> runtime.host.engine.forgetDevice(requireNotNull(value.deviceId))
                    "forget_all" -> runtime.host.engine.forgetAllDevices()
                    "reset_identity" -> runtime.host.engine.resetIdentity()
                    "set_required" -> runtime.setRequirePairing(requireNotNull(value.enabled))
                    "dismiss_refusal" -> runtime.host.engine.dismissRefusal(requireNotNull(value.deviceId))
                    else -> error("Unknown pairing action ${value.action}")
                }
                success()
            }
            "stop" -> {
                DaemonStopMarker.mark()
                thread(name = "wailo-daemon-stop") {
                    Thread.sleep(STOP_RESPONSE_GRACE_MS)
                    onStop()
                }
                success()
            }
            else -> RpcResponse(ok = false, error = "Unknown daemon command: ${request.command}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        executor.shutdownNow()
        handshakeStore.delete(token)
    }

    private fun <T> RpcRequest.decode(serializer: KSerializer<T>): T =
        DaemonJson.decodeFromJsonElement(serializer, payload)

    /** An `Exception` message is already the text a frontend should show; an `Error`'s needs its type to mean anything. */
    private fun Throwable.rpcError(): String {
        val detail = message?.takeIf { it.isNotBlank() }
        val type = this::class.simpleName ?: "failure"
        return when {
            detail == null -> type
            this is Exception -> detail
            else -> "$type: $detail"
        }
    }

    private companion object {
        const val STOP_RESPONSE_GRACE_MS = 100L
    }
}

/**
 * What a client needs to reach the running daemon: where it listens, which control protocol it speaks,
 * and the secret proving the caller is this user. Published as one file so there is no window in which
 * a client can read a port without its matching token (ADR-0059).
 */
internal data class DaemonHandshake(
    val controlPort: Int,
    val protocolVersion: Int,
    val token: ByteArray,
) {
    // ByteArray identity would make two equal handshakes compare unequal, which the tests rely on.
    override fun equals(other: Any?): Boolean = other is DaemonHandshake &&
        controlPort == other.controlPort &&
        protocolVersion == other.protocolVersion &&
        token.contentEquals(other.token)

    override fun hashCode(): Int =
        (controlPort * 31 + protocolVersion) * 31 + token.contentHashCode()
}

@Serializable
private data class DaemonHandshakeDto(
    val controlPort: Int,
    val protocolVersion: Int,
    val token: String,
)

internal interface DaemonHandshakeStore {
    fun write(handshake: DaemonHandshake)
    fun read(): DaemonHandshake
    fun delete(token: ByteArray)
}

internal class FileDaemonHandshakeStore(
    private val path: Path = wailoStateDir().resolve("daemon.json"),
) : DaemonHandshakeStore {
    override fun write(handshake: DaemonHandshake) {
        Files.createDirectories(path.parent)
        setOwnerOnly(path.parent, directory = true)
        val temporary = Files.createTempFile(path.parent, ".daemon-handshake-", ".tmp")
        try {
            // Owner-only before the contents land: the token is a bearer credential for full control of
            // capture, mocks, and breakpoint decisions.
            setOwnerOnly(temporary, directory = false)
            Files.writeString(
                temporary,
                DaemonJson.encodeToString(
                    DaemonHandshakeDto.serializer(),
                    DaemonHandshakeDto(
                        controlPort = handshake.controlPort,
                        protocolVersion = handshake.protocolVersion,
                        token = HexFormat.of().formatHex(handshake.token),
                    ),
                ),
            )
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun read(): DaemonHandshake {
        val dto = try {
            DaemonJson.decodeFromString(DaemonHandshakeDto.serializer(), Files.readString(path))
        } catch (failure: Exception) {
            throw IOException("Wailo daemon handshake is unavailable", failure)
        }
        val token = runCatching { HexFormat.of().parseHex(dto.token) }.getOrNull()
        if (token == null || token.size != TOKEN_BYTES || dto.controlPort !in 1..65535) {
            throw IOException("Wailo daemon handshake is invalid")
        }
        return DaemonHandshake(dto.controlPort, dto.protocolVersion, token)
    }

    override fun delete(token: ByteArray) {
        val current = runCatching { read().token }.getOrNull()
        if (current != null && MessageDigest.isEqual(current, token)) runCatching { Files.deleteIfExists(path) }
    }

    private fun setOwnerOnly(target: Path, directory: Boolean) {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(target, permissions) }
    }
}

internal class MemoryDaemonHandshakeStore : DaemonHandshakeStore {
    @Volatile
    private var handshake: DaemonHandshake? = null

    override fun write(handshake: DaemonHandshake) {
        this.handshake = handshake
    }

    override fun read(): DaemonHandshake =
        handshake ?: throw IOException("Wailo daemon handshake is unavailable")

    override fun delete(token: ByteArray) {
        if (handshake?.let { MessageDigest.isEqual(it.token, token) } == true) handshake = null
    }
}

internal object DaemonWire {
    private const val MAGIC = 0x57414944
    private const val VERSION = 1
    private const val MAX_TOKEN_BYTES = 128
    private const val MAX_REQUEST_BYTES = 256 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 256 * 1024 * 1024

    data class FramedRequest(val token: ByteArray, val json: String)

    fun writeRequest(output: DataOutputStream, token: ByteArray, json: String) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeBytes(output, token)
        writeBytes(output, json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readRequest(input: DataInputStream): FramedRequest {
        require(input.readInt() == MAGIC) { "Not a Wailo daemon request" }
        require(input.readInt() == VERSION) { "Unsupported Wailo daemon protocol version" }
        return FramedRequest(
            token = readBytes(input, MAX_TOKEN_BYTES),
            json = readBytes(input, MAX_REQUEST_BYTES).toString(Charsets.UTF_8),
        )
    }

    fun writeResponse(output: DataOutputStream, json: String) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeBytes(output, json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readResponse(input: DataInputStream): String {
        require(input.readInt() == MAGIC) { "Not a Wailo daemon response" }
        require(input.readInt() == VERSION) { "Unsupported Wailo daemon protocol version" }
        return readBytes(input, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maximum) { "Invalid daemon frame length" }
        return ByteArray(size).also(input::readFully)
    }
}

internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
