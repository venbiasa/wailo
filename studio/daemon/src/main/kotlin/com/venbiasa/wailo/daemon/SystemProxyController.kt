package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.proxy.ProxyUpstream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/** One network service's HTTP and HTTPS proxy settings, exactly as they were before Wailo touched them. */
@Serializable
internal class NetworkServiceProxies(
    val service: String,
    val web: ProxySetting,
    val secure: ProxySetting,
)

@Serializable
internal class ProxySetting(val enabled: Boolean, val server: String, val port: Int) {
    val configured: Boolean get() = enabled && server.isNotEmpty() && port > 0

    fun upstream(): ProxyUpstream? = if (configured) ProxyUpstream(server, port) else null
}

/**
 * What a caller needs to know about the takeover, whether or not a window is open.
 *
 * [snapshot] being non-null is what "Wailo changed your system settings" means, so it doubles as the
 * flag a surface warns from and the thing a restore replays.
 */
internal class SystemProxyState(
    val supported: Boolean,
    val active: Boolean,
    val services: List<String> = emptyList(),
    val chainedTo: ProxyUpstream? = null,
    val error: String? = null,
)

/**
 * Points macOS at the bundled proxy and puts it back afterwards (ADR-0075).
 *
 * Everything goes through `networksetup`, the same tool System Settings drives, so a restore is a real
 * restore rather than an approximation — and so a user who looks at the pane sees the truth. The
 * snapshot is taken before the first change and is the only source for putting things back: reading
 * "what it is now" at restore time would just read Wailo's own settings.
 *
 * It is also written to [statePath] before anything changes, because the failure that matters is the one
 * this process does not survive: a `SIGKILL` would otherwise leave a machine pointed at a proxy that no
 * longer exists, with no record of what it used to be. [recover] is how the next daemon undoes that.
 */
internal class SystemProxyController(
    private val statePath: Path = wailoStateDir().resolve("system-proxy.json"),
) {
    @Volatile
    private var snapshot: List<NetworkServiceProxies>? = null

    /**
     * The proxy that was already configured, if any, so the relay can forward through it. Held from the
     * snapshot rather than re-read: once Wailo is installed, the system's answer is Wailo.
     */
    @Volatile
    private var chained: ProxyUpstream? = null

    val active: Boolean get() = snapshot != null

    fun upstreamFor(@Suppress("UNUSED_PARAMETER") host: String): ProxyUpstream? = chained

    fun state(error: String? = null) = SystemProxyState(
        supported = isSupported,
        active = active,
        services = snapshot?.map { it.service }.orEmpty(),
        chainedTo = chained,
        error = error,
    )

    /**
     * Route this machine's traffic through `127.0.0.1:[port]`.
     *
     * Idempotent by way of the snapshot: a second apply over Wailo's own settings would record them as
     * "what to restore", stranding the user pointed at a proxy that is no longer running.
     */
    @Synchronized
    fun apply(port: Int): SystemProxyState {
        if (!isSupported) return state("system proxy automation needs macOS")
        if (active) return state()
        val services = activeServices()
        if (services.isEmpty()) return state("no active network service to configure")

        val taken = services.map { service ->
            NetworkServiceProxies(service, read(service, WEB), read(service, SECURE))
        }
        // Whichever service was already proxied wins; in practice a machine has one such setting, and
        // guessing between two conflicting ones is worse than taking the first.
        chained = taken.firstNotNullOfOrNull { it.web.upstream() ?: it.secure.upstream() }

        // Written before the first change, so a process that dies mid-apply still leaves the next daemon
        // everything it needs to put the machine back.
        persist(taken)
        val failures = services.mapNotNull { service ->
            val ok = set(service, WEB, port) && set(service, SECURE, port)
            service.takeUnless { ok }
        }
        snapshot = taken
        if (failures.isNotEmpty()) {
            // Partial application is still a change, so it is recorded before being reported — a
            // half-configured machine that Wailo forgot about is the one state with no way back.
            return state("could not configure ${failures.joinToString(", ")}")
        }
        return state()
    }

    /**
     * Follow the listener to a new port, leaving the snapshot alone. Re-applying would instead snapshot
     * Wailo's own settings as "what to restore", which is how a machine ends up permanently pointed at
     * a proxy that is not running.
     */
    @Synchronized
    fun retarget(port: Int) {
        val taken = snapshot ?: return
        taken.forEach { entry ->
            set(entry.service, WEB, port)
            set(entry.service, SECURE, port)
        }
    }

    /** Put every setting back exactly as it was. Safe to call when nothing was applied. */
    @Synchronized
    fun restore(): SystemProxyState {
        val taken = snapshot ?: return state()
        val failures = putBack(taken)
        snapshot = null
        chained = null
        return state(failures.takeIf { it.isNotEmpty() }?.let { "could not restore ${it.joinToString(", ")}" })
    }

    /**
     * Undo a takeover a previous daemon did not live to undo. Called at start-up, before anything else
     * touches the network, because until it runs this machine may have no route out at all.
     */
    @Synchronized
    fun recover(): Boolean {
        val stranded = runCatching {
            Files.readString(statePath).let { DaemonJson.decodeFromString<List<NetworkServiceProxies>>(it) }
        }.getOrNull() ?: return false
        putBack(stranded)
        return true
    }

    private fun putBack(taken: List<NetworkServiceProxies>): List<String> {
        val failures = taken.mapNotNull { entry ->
            val ok = restore(entry.service, WEB, entry.web) && restore(entry.service, SECURE, entry.secure)
            entry.service.takeUnless { ok }
        }
        // Only once the machine is back: a record deleted before the change lands is a takeover nothing
        // knows how to undo.
        if (failures.isEmpty()) runCatching { Files.deleteIfExists(statePath) }
        return failures
    }

    private fun persist(taken: List<NetworkServiceProxies>) {
        runCatching {
            Files.createDirectories(statePath.parent)
            Files.writeString(statePath, DaemonJson.encodeToString(taken))
        }
    }

    private fun activeServices(): List<String> = run("-listallnetworkservices")
        ?.lineSequence()
        ?.drop(1)
        // A leading asterisk is networksetup's mark for a disabled service; configuring one is a change
        // the user will never see take effect.
        ?.filter { it.isNotBlank() && !it.startsWith("*") }
        ?.map { it.trim() }
        ?.toList()
        .orEmpty()

    private fun read(service: String, kind: String): ProxySetting {
        val output = run("-get$kind", service) ?: return ProxySetting(false, "", 0)
        val values = output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.take(separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()
        return ProxySetting(
            enabled = values["Enabled"].equals("Yes", ignoreCase = true),
            server = values["Server"].orEmpty(),
            port = values["Port"]?.toIntOrNull() ?: 0,
        )
    }

    private fun set(service: String, kind: String, port: Int): Boolean =
        run("-set$kind", service, "127.0.0.1", port.toString()) != null &&
            run("-set${kind}state", service, "on") != null

    private fun restore(service: String, kind: String, setting: ProxySetting): Boolean {
        // The server has to be written before the state: turning a proxy on that still points at Wailo
        // would leave the machine broken in exactly the way this is meant to prevent.
        if (setting.server.isNotEmpty() && setting.port > 0) {
            if (run("-set$kind", service, setting.server, setting.port.toString()) == null) return false
        }
        return run("-set${kind}state", service, if (setting.enabled) "on" else "off") != null
    }

    private fun run(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf(NETWORKSETUP) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) null else output
    }.getOrNull()

    companion object {
        private const val NETWORKSETUP = "/usr/sbin/networksetup"
        private const val WEB = "webproxy"
        private const val SECURE = "securewebproxy"
        private const val TIMEOUT_SECONDS = 15L

        val isSupported: Boolean
            get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) &&
                File(NETWORKSETUP).canExecute()
    }
}
