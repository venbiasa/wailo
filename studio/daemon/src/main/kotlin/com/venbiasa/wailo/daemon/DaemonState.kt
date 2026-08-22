package com.venbiasa.wailo.daemon

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val DEFAULT_USB_PORT = 8900

/**
 * Everything the daemon owns on disk — settings, handshake, single-instance lock, log. The daemon is a
 * singleton keyed on this directory, so an end-to-end test that used the real one would fight (and
 * clobber) the developer's live daemon; `WAILO_HOME` points a whole daemon at a scratch directory
 * instead. The launcher hands its environment to the daemon it spawns, so both ends agree without a
 * flag to thread through every frontend.
 */
fun wailoStateDir(): Path =
    System.getenv("WAILO_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".wailo")

/**
 * Where notes about *the machine* live, as opposed to this daemon's own capture state — deliberately not
 * `WAILO_HOME` (ADR-0078).
 *
 * `networksetup` changes one Mac, so the note saying how to put that Mac back has to be findable by
 * whichever daemon runs next, not by the one that happened to make the change. Scoping it to `WAILO_HOME`
 * meant a scratch run could take the machine over and then delete its only undo record along with its
 * temp directory.
 */
fun wailoMachineStateDir(): Path = Path.of(System.getProperty("user.home"), ".wailo")

enum class UsbConnectionStatus {
    ATTACHED,
    CONNECTING,
    WAITING_FOR_APP,
    CONNECTED,
    ERROR,
}

data class UsbDeviceInfo(
    val udid: String,
    val status: UsbConnectionStatus,
    val error: String? = null,
)

enum class AdbConnectionStatus {
    UNAUTHORIZED,
    FORWARDING,
    WAITING_FOR_APP,
    CONNECTED,
    ERROR,
}

data class AdbDeviceInfo(
    val serial: String,
    val name: String,
    val status: AdbConnectionStatus,
    val error: String? = null,
)

internal interface UsbController : Closeable {
    val supported: Boolean
    val devicePort: StateFlow<Int>
    val devices: StateFlow<List<UsbDeviceInfo>>
    fun setDevicePort(port: Int)
}

internal interface AdbController : Closeable {
    val supported: Boolean
    val executablePath: String?
    val devices: StateFlow<List<AdbDeviceInfo>>
    fun setHostPort(port: Int)
}

internal class NoopUsbController(port: Int) : UsbController {
    override val supported = false
    override val devicePort = MutableStateFlow(port)
    override val devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    override fun setDevicePort(port: Int) {
        devicePort.value = port
    }
    override fun close() = Unit
}

internal class NoopAdbController : AdbController {
    override val supported = false
    override val executablePath: String? = null
    override val devices = MutableStateFlow<List<AdbDeviceInfo>>(emptyList())
    override fun setHostPort(port: Int) = Unit
    override fun close() = Unit
}

internal data class DaemonConfig(
    val capturePort: Int,
    val maxRetained: Int,
    val usbPort: Int,
    val requirePairing: Boolean,
    val mcpAccess: Boolean,
    val mcpRedactSecrets: Boolean,
    val idleLingerMinutes: Int,
    /**
     * Where the bundled proxy listens when it is started. Only the port persists: a fresh daemon always
     * starts with proxying off, because a CLI or MCP command that happened to spawn one must not
     * silently re-point anything at it (ADR-0070).
     */
    val proxyPort: Int,
    /**
     * Host patterns whose TLS the proxy terminates. Durable, unlike the proxy switch itself: unlocking a
     * host is a deliberate act the user should not have to repeat, and forgetting it would push them
     * toward unlocking everything (ADR-0071). A `*` entry is deliberately not persisted here — the
     * session-scoped escape hatch is set through the RPC and dies with the daemon.
     */
    val proxyDecryptHosts: List<String>,
    /**
     * Whether the proxy binds beyond loopback. On by default since ADR-0077, which reversed ADR-0074's
     * loopback default: a proxy exists for the clients that cannot host the SDK, and the ones that
     * cannot are mostly not on this machine.
     *
     * What keeps that honest is the other half of ADR-0074, unchanged — nothing here starts a listener.
     * The proxy is still off until something explicitly turns it on, so a persisted `true` is a bind
     * address waiting for a decision, not a relay.
     */
    val proxyLan: Boolean,
)

/** Long enough that stepping away between CLI commands does not cost the session; 0 disables the exit. */
internal const val DEFAULT_IDLE_LINGER_MINUTES = 30

/**
 * A fresh install binds the proxy to every interface (ADR-0077). Named rather than inlined because it is
 * the one default here that decides who can reach a listener, and it should be greppable as that.
 */
internal const val DEFAULT_PROXY_LAN = true

internal const val MAX_IDLE_LINGER_MINUTES = 1_440

internal class DaemonSettings(
    private val path: Path = wailoStateDir().resolve("daemon.properties"),
) {
    @Synchronized
    fun load(): DaemonConfig {
        val values = Properties()
        runCatching { Files.newInputStream(path).use(values::load) }
        return DaemonConfig(
            capturePort = System.getenv("WAILO_CAPTURE_PORT")
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: values.getProperty(CAPTURE_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: 8899,
            maxRetained = System.getenv("WAILO_MAX_RETAINED")
                ?.toIntOrNull()
                ?.takeIf { it in 100..100_000 }
                ?: values.getProperty(MAX_RETAINED)?.toIntOrNull()?.takeIf { it in 100..100_000 }
                ?: 10_000,
            usbPort = values.getProperty(USB_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_USB_PORT,
            requirePairing = values.getProperty(REQUIRE_PAIRING)?.toBooleanStrictOrNull() ?: false,
            // Both default to the safer reading of "on": MCP works out of the box, but what an agent
            // reads is redacted until the user decides otherwise (ADR-0059).
            mcpAccess = values.getProperty(MCP_ACCESS)?.toBooleanStrictOrNull() ?: true,
            mcpRedactSecrets = values.getProperty(MCP_REDACT_SECRETS)?.toBooleanStrictOrNull() ?: true,
            idleLingerMinutes = System.getenv("WAILO_IDLE_LINGER_MINUTES")
                ?.toIntOrNull()
                ?.takeIf { it in 0..MAX_IDLE_LINGER_MINUTES }
                ?: values.getProperty(IDLE_LINGER_MINUTES)
                    ?.toIntOrNull()
                    ?.takeIf { it in 0..MAX_IDLE_LINGER_MINUTES }
                ?: DEFAULT_IDLE_LINGER_MINUTES,
            proxyPort = System.getenv("WAILO_PROXY_PORT")
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: values.getProperty(PROXY_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: DEFAULT_PROXY_PORT,
            proxyDecryptHosts = values.getProperty(PROXY_DECRYPT_HOSTS)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != "*" }
                .orEmpty(),
            proxyLan = values.getProperty(PROXY_LAN)?.toBooleanStrictOrNull() ?: DEFAULT_PROXY_LAN,
        )
    }

    @Synchronized
    fun update(transform: (DaemonConfig) -> DaemonConfig) {
        val config = transform(load())
        val values = Properties().apply {
            setProperty(CAPTURE_PORT, config.capturePort.toString())
            setProperty(MAX_RETAINED, config.maxRetained.toString())
            setProperty(USB_PORT, config.usbPort.toString())
            setProperty(REQUIRE_PAIRING, config.requirePairing.toString())
            setProperty(MCP_ACCESS, config.mcpAccess.toString())
            setProperty(MCP_REDACT_SECRETS, config.mcpRedactSecrets.toString())
            setProperty(IDLE_LINGER_MINUTES, config.idleLingerMinutes.toString())
            setProperty(PROXY_PORT, config.proxyPort.toString())
            setProperty(PROXY_DECRYPT_HOSTS, config.proxyDecryptHosts.filterNot { it == "*" }.joinToString(","))
            setProperty(PROXY_LAN, config.proxyLan.toString())
        }
        Files.createDirectories(path.parent)
        setOwnerOnly(path.parent, directory = true)
        val temporary = Files.createTempFile(path.parent, ".daemon-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { values.store(it, "Wailo daemon settings") }
            setOwnerOnly(temporary, directory = false)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
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

    private companion object {
        const val CAPTURE_PORT = "capturePort"
        const val MAX_RETAINED = "maxRetained"
        const val USB_PORT = "usbPort"
        const val REQUIRE_PAIRING = "requirePairing"
        const val MCP_ACCESS = "mcpAccess"
        const val MCP_REDACT_SECRETS = "mcpRedactSecrets"
        const val IDLE_LINGER_MINUTES = "idleLingerMinutes"
        const val PROXY_PORT = "proxyPort"
        const val PROXY_DECRYPT_HOSTS = "proxyDecryptHosts"
        const val PROXY_LAN = "proxyLan"
    }
}

internal object DaemonStopMarker {
    private val path: Path
        get() = wailoStateDir().resolve("daemon.stopped")

    fun mark() {
        Files.createDirectories(path.parent)
        Files.writeString(path, "stopped")
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }

    fun isMarked(): Boolean = Files.exists(path)
}

/**
 * Guarantees exactly one process owns a named piece of the state directory. The daemon holds it for the
 * handshake file: the capture-port bind used to arbitrate launch races on its own, but the control port is
 * auto-chosen now (ADR-0059), so two daemons started against different capture ports would both bind and
 * the later one would overwrite the handshake — leaving the earlier daemon running with no client able to
 * reach it. The menu bar agent holds its own for the same reason, one icon instead of one handshake
 * (ADR-0065). An OS file lock is released on crash or kill, which a pid file would not be.
 */
class DaemonSingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun tryAcquire(
            path: Path = wailoStateDir().resolve("daemon.lock"),
        ): DaemonSingleInstanceLock? {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { channel.close() }
                return null
            }
            return DaemonSingleInstanceLock(channel, lock)
        }
    }
}
