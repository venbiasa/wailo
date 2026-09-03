package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.daemon.adb.AdbDeviceManager
import com.venbiasa.wailo.daemon.usb.UsbDeviceManager
import com.venbiasa.wailo.engine.pairing.InMemoryPairingKeyStore
import com.venbiasa.wailo.host.HeadlessHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun main() {
    // Taken before anything is bound or written: a second daemon must not publish a handshake over the
    // one clients are already using. Held for the life of the process, released by the OS if it dies.
    val instanceLock = DaemonSingleInstanceLock.tryAcquire()
    if (instanceLock == null) {
        System.err.println("wailo-daemon: another daemon already owns this user's Wailo state")
        exitProcess(0)
    }
    val settings = DaemonSettings()
    val config = settings.load()
    // Before anything binds or dials: if the last daemon was killed while it owned the system proxy,
    // this machine is currently pointed at a listener that no longer exists (ADR-0075).
    val systemProxy = SystemProxyController.forThisMachine()
    if (systemProxy.recover()) {
        System.err.println("wailo-daemon: restored the system proxy settings a previous run left behind")
    } else if (systemProxy.releaseStranded(config.proxyPort)) {
        // No record, but the machine points at a Wailo proxy that is not there — a takeover whose daemon
        // died with the only note of it. Nothing else will ever undo this one, so start-up does (ADR-0078).
        System.err.println("wailo-daemon: turned off a system proxy left pointing at a proxy that is gone")
    }
    val keyStore = if (KeychainPairingKeyStore.isSupported) {
        // A second, isolated daemon may need a genuinely independent Studio identity, not just its own
        // ports and preferences. Production keeps the stable default service; smoke/E2E runs can opt in.
        System.getenv("WAILO_KEYCHAIN_SERVICE")
            ?.takeIf { it.isNotBlank() }
            ?.let(::KeychainPairingKeyStore)
            ?: KeychainPairingKeyStore()
    } else {
        InMemoryPairingKeyStore()
    }
    // The proxy's signing root, on the same terms and for the same reason (ADR-0073). Without a
    // Keychain it lives only for this process, so no machine ends up with a universal signing key in a
    // plain file.
    val certificateAuthority = WailoCertificateAuthority(
        if (KeychainCertificateAuthorityStore.isSupported) {
            System.getenv("WAILO_KEYCHAIN_SERVICE")
                ?.takeIf { it.isNotBlank() }
                ?.let(::KeychainCertificateAuthorityStore)
                ?: KeychainCertificateAuthorityStore()
        } else {
            EphemeralCertificateAuthorityStore()
        },
    )
    // Opened before the host, because the engine is built around it and because clearing a dead
    // daemon's leftovers is the first thing this process owes the disk.
    val bodies = SpoolBodyStore.open()
    val host = try {
        HeadlessHost.start(
            port = config.capturePort,
            maxRetained = config.maxRetained,
            requirePairing = config.requirePairing,
            pairingKeyStore = keyStore,
            bodyStore = bodies,
        )
    } catch (failure: Exception) {
        bodies.close()
        System.err.println("wailo-daemon: ${failure.message}")
        exitProcess(1)
    }
    // Retire a slice of the oldest traffic rather than a fixed number of bytes: the store re-checks
    // after every further 32 MiB spooled, so a volume that is still filling keeps trimming until it
    // isn't. Forgetting the start of a capture beats refusing to record the rest of it.
    bodies.onPressure = {
        host.engine.evictOldest((host.engine.exchanges.value.size / 10).coerceAtLeast(1))
    }
    val runtime = DaemonRuntime(
        host = host,
        usb = UsbDeviceManager(host.engine, config.usbPort),
        adb = AdbDeviceManager(host.engine.port),
        settings = settings,
        pairingSupported = KeychainPairingKeyStore.isSupported,
        mcpAccess = config.mcpAccess,
        mcpRedactSecrets = config.mcpRedactSecrets,
        proxyPort = config.proxyPort,
        proxyDecryptHosts = config.proxyDecryptHosts,
        proxyLan = config.proxyLan,
        certificateAuthority = certificateAuthority,
        systemProxy = systemProxy,
    )
    // A killed daemon can leave an emulator pointed at a listener that no longer exists (ADR-0090).
    if (runtime.recoverProvisionedTargets()) {
        System.err.println("wailo-daemon: cleared an emulator proxy a previous run left behind")
    }
    val targetRecovery = ProxyTargetRecoveryWatchdog(
        recover = { runtime.recoverProvisionedTargets() },
    ).also {
        it.start()
    }
    val stopped = CountDownLatch(1)
    val stopping = AtomicBoolean()
    lateinit var server: DaemonServer
    lateinit var idleWatchdog: DaemonIdleWatchdog
    val stop = {
        if (stopping.compareAndSet(false, true)) {
            runCatching { targetRecovery.close() }
            runCatching { idleWatchdog.close() }
            runCatching { server.close() }
            runtime.close()
            runCatching { bodies.close() }
            runCatching { instanceLock.close() }
            stopped.countDown()
        }
    }
    server = try {
        DaemonServer(runtime, onStop = stop).also(DaemonServer::start)
    } catch (failure: Exception) {
        runtime.close()
        bodies.close()
        instanceLock.close()
        System.err.println("wailo-daemon: could not publish the control channel: ${failure.message}")
        exitProcess(1)
    }
    val captureActivity = DaemonCaptureActivity(host.engine.exchanges, host.engine.pausedExchanges)
    idleWatchdog = DaemonIdleWatchdog(
        lingerMillis = config.idleLingerMinutes * 60_000L,
        // A running proxy counts as a reference in its own right: something out there has its network
        // pointed at this process, and exiting because no window is open would take that network down.
        referenced = { server.references > 0 || runtime.proxyRunning },
        // A capturing app extends the window the same way a CLI command does, rather than pinning the
        // daemon outright: what earns the process its life is traffic, not a socket a silent app happens
        // to be holding.
        lastActivityAtMillis = { maxOf(server.lastActivityAtMillis, captureActivity.lastCaptureAtMillis()) },
        // Deliberately not DaemonStopMarker: nothing asked for this, so the next frontend to open must be
        // free to start a daemon again without first clearing an explicit stop.
        onIdle = stop,
    ).also(DaemonIdleWatchdog::start)
    DaemonStopMarker.clear()
    Runtime.getRuntime().addShutdownHook(Thread(stop, "wailo-daemon-shutdown"))
    try {
        stopped.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        stop()
    }
}
