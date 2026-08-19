package com.venbiasa.wailo.menubar

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.daemon.DaemonLauncher
import com.venbiasa.wailo.daemon.DaemonSingleInstanceLock
import com.venbiasa.wailo.daemon.wailoStateDir
import java.awt.SystemTray
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The menu bar agent: the daemon made visible (ADR-0065). It draws one item for as long as a daemon is
 * alive and exits with it, so the presence of the icon is the answer to "is Wailo still running".
 *
 * It is deliberately *not* a reference (ADR-0062). If it held one, the thing whose lifetime it reports
 * would be kept alive by the reporting, and the daemon could never idle out.
 */
fun main() {
    // Hands the icon to macOS as a template image, so the system tints it to whatever the menu bar is.
    // Set here rather than on the command line because AWT reads it once, when the first tray icon is
    // created, and an agent started any other way must still get a visible glyph. Ignored off macOS.
    System.setProperty("apple.awt.enableTemplateImages", "true")
    // One icon per state directory. Held for the whole process, which is also how a frontend's launcher
    // asks "is an agent already up" without a protocol for it.
    val lock = DaemonSingleInstanceLock.tryAcquire(DaemonLauncher.menubarLockPath()) ?: run {
        note("another agent already draws the item")
        return
    }
    try {
        if (!SystemTray.isSupported()) {
            note("this desktop has no system tray; not asking again")
            // Record it so this machine is asked once, not once per frontend command.
            runCatching { Files.writeString(DaemonLauncher.menubarUnsupportedPath(), "unsupported") }
            return
        }
        val mark = TrayIcon.mark() ?: run {
            note("the app mark is missing from this install")
            return
        }
        // autoStart = false is the whole point: an agent that started a daemon would resurrect the one the
        // user just quit, and would do it from a menu that is supposed to only ever report.
        val attempt = runBlocking { runCatching { DaemonClient.connect(autoStart = false) } }
        val client = attempt.getOrElse { failure ->
            // Name the directory: an agent is one process per state dir, and a WAILO_HOME the frontend does
            // not share is the difference between "no daemon" and "not that daemon".
            note("no daemon answered in ${wailoStateDir()}: ${failure.message}")
            return
        }
        try {
            run(client, mark)
        } finally {
            client.close()
        }
    } finally {
        lock.close()
        // Touching the tray started AWT, whose event thread is not a daemon thread and can outlive the
        // last removed icon. Falling off the end of main would leave a process that no longer draws
        // anything — the exact thing this agent exists to make impossible.
        exitProcess(0)
    }
}

private fun run(client: DaemonClient, mark: BufferedImage) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val done = CountDownLatch(1)
    val stopping = AtomicBoolean()
    lateinit var menu: TrayMenu

    fun shutdown(because: String) {
        if (!stopping.compareAndSet(false, true)) return
        note("taking the item down: $because")
        menu.remove()
        scope.cancel()
        done.countDown()
    }

    menu = TrayMenu(
        mark = mark,
        actions = MenuActions(
            showStudio = {
                scope.launch {
                    // Raise the one that is here; start one only when there is none, so clicking this never
                    // leaves a second Studio behind.
                    if (client.studioAttached.value) {
                        runCatching { client.requestShowStudio() }
                    } else {
                        StudioLauncher.launch()
                    }
                }
            },
            setCapturing = { enabled -> scope.launch { runCatching { client.setCapturing(enabled) } } },
            setMapLocalEnabled = { enabled ->
                scope.launch { runCatching { client.setMapLocalEnabled(enabled) } }
            },
            setBreakpointsEnabled = { enabled ->
                scope.launch { runCatching { client.setBreakpointsEnabled(enabled) } }
            },
            // The filter travels as one message, so a list toggle has to resend the other list untouched.
            // Read it at click time rather than from the rendered state: the poll behind the open menu may
            // be a tick old, and re-sending a stale host list would drop hosts added elsewhere.
            setAllowlistEnabled = { enabled ->
                scope.launch {
                    runCatching {
                        val filter = client.captureFilter.value
                        client.updateCaptureFilter(
                            allowlistEnabled = enabled,
                            allowPatterns = filter.allow_patterns,
                            blocklistEnabled = filter.blocklist_enabled,
                            blockPatterns = filter.block_patterns,
                        )
                    }
                }
            },
            setBlocklistEnabled = { enabled ->
                scope.launch {
                    runCatching {
                        val filter = client.captureFilter.value
                        client.updateCaptureFilter(
                            allowlistEnabled = filter.allowlist_enabled,
                            allowPatterns = filter.allow_patterns,
                            blocklistEnabled = enabled,
                            blockPatterns = filter.block_patterns,
                        )
                    }
                }
            },
            quit = {
                scope.launch {
                    // Frontends first: they exit on the broadcast, then the daemon goes. The other order
                    // leaves Studio polling a corpse and relaunching it on the next tick.
                    runCatching { client.requestQuit() }
                    delay(QUIT_GRACE_MS)
                    runCatching { client.stopDaemon() }
                    shutdown("the user quit Wailo")
                }
            },
        ),
    )
    menu.install()
    // The one line that says the item should be on screen: with no window of its own, an agent that is
    // running is otherwise indistinguishable from one whose icon the desktop silently dropped.
    val size = SystemTray.getSystemTray().trayIconSize
    note("drawing the item at ${size.width}x${size.height}")

    scope.launch {
        while (isActive) {
            val filter = client.captureFilter.value
            menu.update(
                MenuState(
                    listening = client.listening.value,
                    lanAddress = client.lanAddress.value,
                    capturePort = client.capturePort.value,
                    capturing = client.capturing.value,
                    mapLocalEnabled = client.mapLocalEnabled.value,
                    breakpointsEnabled = client.breakpointsEnabled.value,
                    allowlistEnabled = filter.allowlist_enabled,
                    blocklistEnabled = filter.blocklist_enabled,
                    allowlistConfigured = filter.allow_patterns.isNotEmpty(),
                    blocklistConfigured = filter.block_patterns.isNotEmpty(),
                    studioAttached = client.studioAttached.value,
                ),
            )
            delay(REFRESH_MS)
        }
    }

    // The icon's only promise is that a daemon is alive, so losing it for good is the agent's cue to go.
    // A grace window keeps a daemon being *replaced* (a protocol bump restarts it) from taking the icon
    // down with it, since the replacement arrives within a reconnect.
    scope.launch {
        var goneSince: Long? = null
        while (isActive) {
            if (client.connected.value) {
                goneSince = null
            } else {
                val since = goneSince ?: System.currentTimeMillis().also { goneSince = it }
                if (System.currentTimeMillis() - since >= DAEMON_GONE_AFTER_MS) shutdown("the daemon is gone")
            }
            delay(REFRESH_MS)
        }
    }

    done.await()
}

/** Goes to the agent's log, since the whole process is one icon and has nowhere else to say anything. */
private fun note(message: String) = System.err.println("wailo-menubar: $message")

private const val REFRESH_MS = 500L
private const val QUIT_GRACE_MS = 400L
private const val DAEMON_GONE_AFTER_MS = 5_000L
