package com.venbiasa.wailo.daemon

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class ProxyTargetRecoveryWatchdog(
    private val recover: () -> Unit,
    private val intervalMillis: Long = 5_000,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var worker: Thread? = null

    fun start() {
        worker = thread(name = "wailo-proxy-target-recovery", isDaemon = true) {
            while (!closed.get()) {
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!closed.get()) recover()
            }
        }
    }

    override fun close() {
        closed.set(true)
        worker?.interrupt()
    }
}
