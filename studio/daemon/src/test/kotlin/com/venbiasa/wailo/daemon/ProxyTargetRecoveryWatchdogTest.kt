package com.venbiasa.wailo.daemon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class ProxyTargetRecoveryWatchdogTest {
    @Test
    fun retriesRecoveryUntilAnOfflineTargetCanReappear() {
        val attempts = AtomicInteger()
        val retried = CountDownLatch(1)
        ProxyTargetRecoveryWatchdog(
            recover = {
                if (attempts.incrementAndGet() >= 2) retried.countDown()
            },
            intervalMillis = 10,
        ).use { watchdog ->
            watchdog.start()

            assertTrue(retried.await(2, TimeUnit.SECONDS))
        }
    }
}
