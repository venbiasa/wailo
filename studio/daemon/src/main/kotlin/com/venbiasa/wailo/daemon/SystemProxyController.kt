package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.proxy.ProxyUpstream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
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

    /** Whether this is Wailo's own listener rather than a setting that predates it. */
    fun ours(ourPort: Int): Boolean = configured && port == ourPort && server in LOOPBACK_NAMES

    /**
     * The same setting, unless it is Wailo's own — in which case "nothing was configured". A leftover
     * from a takeover nobody undid is not a setting worth preserving; recording it as one is what made
     * the takeover permanent (ADR-0078).
     */
    fun unlessOurs(ourPort: Int): ProxySetting = if (ours(ourPort)) ProxySetting(false, "", 0) else this
}

private val LOOPBACK_NAMES = setOf("127.0.0.1", "::1", "localhost")

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
internal fun interface NetworkSetup {
    /** The command's output, or null when it failed. */
    fun run(arguments: List<String>): String?
}

internal class SystemProxyController private constructor(
    private val statePath: Path,
    /** Null where there is no `networksetup` to drive, which is also what a stand-in machine looks like. */
    private val networksetup: NetworkSetup?,
    /** Whether something answers on a loopback port — how a live takeover is told from an abandoned one. */
    private val listens: (Int) -> Boolean,
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

    private val supported: Boolean get() = networksetup != null

    fun state(error: String? = null) = SystemProxyState(
        supported = supported,
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
        if (!supported) return state("system proxy automation needs macOS")
        if (active) return state()
        val services = activeServices()
        if (services.isEmpty()) return state("no active network service to configure")

        // An outstanding record outranks whatever the machine says now: it was written before the first
        // change, so it is the last description of the machine that predates Wailo. Re-reading here is
        // how a takeover across a daemon restart used to record Wailo's own address as "what was there
        // before", making the change permanent and pointing the relay at itself (ADR-0078).
        val taken = readRecord() ?: services.map { service ->
            NetworkServiceProxies(
                service,
                read(service, WEB).unlessOurs(port),
                read(service, SECURE).unlessOurs(port),
            )
        }
        // Whichever service was already proxied wins; in practice a machine has one such setting, and
        // guessing between two conflicting ones is worse than taking the first. It cannot be Wailo
        // itself, which `unlessOurs` has already dropped — a relay chained to its own listener is a loop.
        chained = taken.firstNotNullOfOrNull { it.web.upstream() ?: it.secure.upstream() }

        if (!persist(taken)) {
            chained = null
            return state("could not record the current system proxy; nothing was changed")
        }
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
        val stranded = readRecord() ?: return false
        putBack(stranded)
        return true
    }

    /**
     * Turn off any service still pointed at `127.0.0.1:[port]` when nothing is listening there.
     *
     * This is the backstop for a takeover with no record — the daemon that applied it was killed, or
     * wrote its record somewhere that no longer exists. [restore] cannot help there, because it can only
     * replay a snapshot this process holds, so a machine could be left routing through a listener that
     * had gone, with nothing anywhere that knew to undo it (ADR-0078).
     *
     * The invariant is the one a user actually feels: with Wailo's proxy not running, nothing points at
     * it. So the test is the machine's current state, not a memory of having changed it. Turning the
     * proxy off rather than reinstating a previous setting is deliberate — without a record there is
     * nothing to reinstate, and a direct connection is the state every machine can reach.
     *
     * [listenerIsOurs] is for the one case that "does the port answer" reads backwards: this process
     * holds the listener and did not take the machine over, so the thing keeping the sweep away is us.
     * Left alone it is self-perpetuating — the machine loses its network whenever the proxy is down, and
     * starting the proxy to get back online is what disarms the only cleanup there is (ADR-0083).
     */
    @Synchronized
    fun releaseStranded(port: Int, listenerIsOurs: Boolean = false): Boolean {
        // A live listener we cannot account for means the takeover is working, and may belong to another
        // daemon: leave it be.
        if (!supported || active || (listens(port) && !listenerIsOurs)) return false
        val released = activeServices().filter { service ->
            val web = read(service, WEB).ours(port)
            val secure = read(service, SECURE).ours(port)
            if (web) run("-set${WEB}state", service, "off")
            if (secure) run("-set${SECURE}state", service, "off")
            web || secure
        }
        return released.isNotEmpty()
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

    private fun readRecord(): List<NetworkServiceProxies>? = runCatching {
        DaemonJson.decodeFromString<List<NetworkServiceProxies>>(Files.readString(statePath))
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun persist(taken: List<NetworkServiceProxies>): Boolean =
        writeAtomically(statePath, DaemonJson.encodeToString(taken))

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

    private fun run(vararg arguments: String): String? = networksetup?.run(arguments.toList())

    companion object {
        internal const val WEB = "webproxy"
        internal const val SECURE = "securewebproxy"

        val isSupported: Boolean get() = systemNetworkSetup() != null

        /**
         * The only construction that can reconfigure the Mac this process runs on, and it takes the
         * machine's own record path with it.
         *
         * The two used to be independent arguments with defaults, so a caller could redirect the record
         * to a throwaway path and still drive the real `networksetup` — which is a takeover that outlives
         * the process with nothing left anywhere to undo it, the one state ADR-0075 has no answer for. A
         * test did exactly that and silently took over the machine running the suite (ADR-0083).
         */
        fun forThisMachine(): SystemProxyController = SystemProxyController(
            wailoMachineStateDir().resolve("system-proxy.json"),
            systemNetworkSetup(),
            ::loopbackAnswers,
        )

        /**
         * A controller over a stand-in machine, with its record beside it. There is no way to pair this
         * with the real `networksetup`: [systemNetworkSetup] is file-private, so [forThisMachine] is the
         * only door to it.
         */
        fun over(
            machine: NetworkSetup?,
            statePath: Path,
            listens: (Int) -> Boolean = { false },
        ): SystemProxyController = SystemProxyController(statePath, machine, listens)
    }
}

private const val NETWORKSETUP = "/usr/sbin/networksetup"
private const val TIMEOUT_SECONDS = 15L

/** Only ever dialled on loopback, where a listener answers immediately or does not exist. */
private const val PROBE_TIMEOUT_MS = 250

private fun systemNetworkSetup(): NetworkSetup? {
    val onAMac = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
    if (!onAMac || !File(NETWORKSETUP).canExecute()) return null
    return NetworkSetup { arguments -> runMachineProcess(listOf(NETWORKSETUP) + arguments, TIMEOUT_SECONDS) }
}

private fun loopbackAnswers(port: Int): Boolean = runCatching {
    Socket().use {
        it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
        true
    }
}.getOrDefault(false)
