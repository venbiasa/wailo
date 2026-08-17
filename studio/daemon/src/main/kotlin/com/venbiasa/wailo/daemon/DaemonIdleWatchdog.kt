package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.PausedExchange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.StateFlow

/**
 * Stops the daemon once nothing refers to it any more (ADR-0062). A reference is a frontend's held
 * presence socket, so this only decides *when* an unreferenced daemon exits, never whether something is
 * still using it.
 *
 * The linger window exists for everything that refers to the daemon without holding it open: a one-shot
 * CLI command, and a device that is capturing. Without it, `wailo-cli status` on an idle machine would
 * start a daemon and immediately kill it again.
 */
internal class DaemonIdleWatchdog(
    private val lingerMillis: Long,
    private val referenced: () -> Boolean,
    private val lastActivityAtMillis: () -> Long,
    private val onIdle: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkIntervalMillis: Long = (lingerMillis / 4).coerceIn(MIN_CHECK_MS, MAX_CHECK_MS),
) : AutoCloseable {
    private val closed = AtomicBoolean()

    @Volatile
    private var lastReferencedAt = clock()

    val enabled: Boolean get() = lingerMillis > 0

    fun start() {
        if (!enabled) return
        thread(name = "wailo-daemon-idle", isDaemon = true) {
            while (!closed.get()) {
                try {
                    Thread.sleep(checkIntervalMillis)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (closed.get()) return@thread
                if (isIdle()) {
                    onIdle()
                    return@thread
                }
            }
        }
    }

    /**
     * Measures the window from whichever came last, a reference being released or a request arriving, so
     * a frontend that quits after hours of polling still gets the full linger rather than an instant exit.
     */
    internal fun isIdle(): Boolean {
        if (!enabled) return false
        val now = clock()
        if (referenced()) {
            lastReferencedAt = now
            return false
        }
        return now - maxOf(lastReferencedAt, lastActivityAtMillis()) >= lingerMillis
    }

    override fun close() {
        closed.set(true)
    }

    private companion object {
        const val MIN_CHECK_MS = 1_000L
        const val MAX_CHECK_MS = 30_000L
    }
}

/**
 * Capturing counts as using the daemon; merely being plugged in does not (ADR-0062). A connected app
 * that has gone quiet — a phone left on the desk with the debug build running — would otherwise pin the
 * daemon for as long as the cable is in, which is the state this whole mechanism exists to end.
 *
 * Sampled rather than collected, because the watchdog already wakes on its own schedule and the check
 * interval is orders of magnitude finer than the linger it feeds.
 */
internal class DaemonCaptureActivity(
    private val exchanges: StateFlow<List<CapturedExchange>>,
    private val holds: StateFlow<List<PausedExchange>>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seen = fingerprint()
    private var lastCaptureAt = clock()

    @Synchronized
    fun lastCaptureAtMillis(): Long {
        val current = fingerprint()
        if (current != seen) {
            seen = current
            lastCaptureAt = clock()
        }
        return lastCaptureAt
    }

    // Count alone would miss traffic arriving into a full retention buffer, where the oldest row leaves
    // as the newest arrives; the newest id moves whether or not the size does.
    private fun fingerprint() = listOf(
        exchanges.value.size,
        exchanges.value.lastOrNull()?.exchange?.id,
        holds.value.size,
        holds.value.lastOrNull()?.correlationId,
    )
}
