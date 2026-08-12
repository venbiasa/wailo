package com.venbiasa.wailo.cli

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
import kotlinx.coroutines.runBlocking

internal const val DEFAULT_CONTROL_PORT = 8898

/**
 * Loopback-only command channel between one-shot CLI invocations and the long-lived capture process.
 * Arguments are length-framed rather than shell-tokenized, so quoted values arrive exactly as the
 * caller's shell parsed them. A per-run secret in an owner-only file prevents other local users from
 * reading bodies or changing rules merely because they can reach loopback.
 */
internal class ControlServer(
    private val host: HeadlessHost,
    private val port: Int,
    private val tokenStore: ControlTokenStore = FileControlTokenStore(),
) : AutoCloseable {
    private val server = ServerSocket()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(LOOPBACK, port))
        try {
            tokenStore.write(port, token)
        } catch (failure: Exception) {
            server.close()
            executor.shutdownNow()
            throw failure
        }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "Control server already started" }
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            executor.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val result = try {
                val request = ControlProtocol.readRequest(input)
                if (!MessageDigest.isEqual(token, request.token)) {
                    CommandResult("Control authentication failed", exitCode = 2)
                } else {
                    val parsed = parseArgs(request.args.toTypedArray())
                    if (parsed == null) {
                        CommandResult("Invalid command arguments", exitCode = 2)
                    } else {
                        runBlocking { dispatch(host, parsed) }
                    }
                }
            } catch (failure: Exception) {
                CommandResult("Control command failed: ${failure.message}", exitCode = 1)
            }
            ControlProtocol.writeResponse(output, result)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        executor.shutdownNow()
        tokenStore.delete(port, token)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}

internal class ControlClient(
    private val port: Int,
    private val tokenStore: ControlTokenStore = FileControlTokenStore(),
) {
    fun execute(args: Array<String>, timeoutSeconds: Long): CommandResult {
        val token = tokenStore.read(port)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMillis(timeoutSeconds)
            val output = DataOutputStream(socket.getOutputStream())
            ControlProtocol.writeRequest(output, token, args.asList())
            ControlProtocol.readResponse(DataInputStream(socket.getInputStream()))
        }
    }
}

internal interface ControlTokenStore {
    fun write(port: Int, token: ByteArray)
    fun read(port: Int): ByteArray
    fun delete(port: Int, token: ByteArray)
}

internal class FileControlTokenStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".wailo"),
) : ControlTokenStore {
    override fun write(port: Int, token: ByteArray) {
        Files.createDirectories(directory)
        setOwnerOnly(directory, isDirectory = true)
        val target = path(port)
        val temporary = Files.createTempFile(directory, ".control-$port-", ".tmp")
        try {
            Files.writeString(temporary, HexFormat.of().formatHex(token))
            setOwnerOnly(temporary, isDirectory = false)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun read(port: Int): ByteArray {
        val raw = try {
            Files.readString(path(port)).trim()
        } catch (failure: Exception) {
            throw IOException("control token is unavailable; start `wailo-cli serve` first", failure)
        }
        if (raw.length != 64) throw IOException("control token is invalid; restart `wailo-cli serve`")
        return try {
            HexFormat.of().parseHex(raw)
        } catch (failure: IllegalArgumentException) {
            throw IOException("control token is invalid; restart `wailo-cli serve`", failure)
        }
    }

    override fun delete(port: Int, token: ByteArray) {
        val target = path(port)
        val current = runCatching { HexFormat.of().parseHex(Files.readString(target).trim()) }.getOrNull()
        if (current != null && MessageDigest.isEqual(current, token)) runCatching { Files.deleteIfExists(target) }
    }

    private fun path(port: Int): Path = directory.resolve("control-$port.token")

    private fun setOwnerOnly(path: Path, isDirectory: Boolean) {
        val permissions = if (isDirectory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }
}

internal class MemoryControlTokenStore : ControlTokenStore {
    @Volatile
    private var token: ByteArray? = null

    override fun write(port: Int, token: ByteArray) {
        this.token = token.copyOf()
    }

    override fun read(port: Int): ByteArray =
        token?.copyOf() ?: throw IOException("control token unavailable")

    override fun delete(port: Int, token: ByteArray) {
        if (this.token?.let { MessageDigest.isEqual(it, token) } == true) this.token = null
    }
}

private object ControlProtocol {
    private const val MAGIC = 0x5741494C
    private const val VERSION = 1
    private const val MAX_ARGUMENTS = 256
    private const val MAX_VALUE_BYTES = 1_048_576
    private const val MAX_RESPONSE_BYTES = 8_388_608

    data class Request(val token: ByteArray, val args: List<String>)

    fun writeRequest(output: DataOutputStream, token: ByteArray, args: List<String>) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeBytes(output, token)
        output.writeInt(args.size)
        args.forEach { writeBytes(output, it.toByteArray(Charsets.UTF_8)) }
        output.flush()
    }

    fun readRequest(input: DataInputStream): Request {
        require(input.readInt() == MAGIC) { "not a Wailo control request" }
        require(input.readInt() == VERSION) { "unsupported control protocol version" }
        val token = readBytes(input, 128)
        val count = input.readInt()
        require(count in 1..MAX_ARGUMENTS) { "invalid argument count" }
        return Request(token, List(count) { readBytes(input, MAX_VALUE_BYTES).toString(Charsets.UTF_8) })
    }

    fun writeResponse(output: DataOutputStream, result: CommandResult) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeInt(result.exitCode)
        writeBytes(output, result.message.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readResponse(input: DataInputStream): CommandResult {
        require(input.readInt() == MAGIC) { "not a Wailo control response" }
        require(input.readInt() == VERSION) { "unsupported control protocol version" }
        val exitCode = input.readInt()
        val message = readBytes(input, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
        return CommandResult(message, exitCode)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maximum) { "invalid control value length" }
        return ByteArray(size).also(input::readFully)
    }
}

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
private const val CONNECT_TIMEOUT_MS = 1_500

private fun readTimeoutMillis(timeoutSeconds: Long): Int =
    ((timeoutSeconds.coerceIn(0, 86_400) + 5) * 1_000).toInt()
