package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.protocol.HttpExchange
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class DaemonIdleWatchdogTest {
    @Test
    fun neverIdlesWhileSomethingRefersToIt() {
        val clock = TestClock()
        val watchdog = watchdog(clock, referenced = { true })

        clock.advanceMinutes(120)

        assertFalse(watchdog.isIdle())
    }

    @Test
    fun givesTheWholeLingerToWhoeverComesAfterTheLastReferenceLeaves() {
        val clock = TestClock()
        var referenced = true
        val watchdog = watchdog(clock, referenced = { referenced })

        clock.advanceMinutes(120)
        assertFalse(watchdog.isIdle())
        referenced = false

        // Measured from the release, not from the poll that happened to precede it: a frontend open for
        // hours must not leave behind a daemon that exits the instant it quits.
        clock.advanceMinutes(29)
        assertFalse(watchdog.isIdle())
        clock.advanceMinutes(1)
        assertTrue(watchdog.isIdle())
    }

    @Test
    fun aOneShotRequestPostponesTheExitWithoutHoldingAReference() {
        val clock = TestClock()
        var lastActivity = 0L
        val watchdog = watchdog(clock, referenced = { false }, lastActivity = { lastActivity })

        clock.advanceMinutes(29)
        lastActivity = clock.now

        clock.advanceMinutes(29)
        assertFalse(watchdog.isIdle())
        clock.advanceMinutes(1)
        assertTrue(watchdog.isIdle())
    }

    @Test
    fun capturedTrafficPostponesTheExitButAQuietConnectedAppDoesNot() {
        val clock = TestClock()
        val exchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
        val capture = DaemonCaptureActivity(exchanges, MutableStateFlow(emptyList())) { clock.now }
        val watchdog = watchdog(clock, referenced = { false }, lastActivity = capture::lastCaptureAtMillis)

        clock.advanceMinutes(29)
        exchanges.value = listOf(exchange("first"))
        assertFalse(watchdog.isIdle())

        // The app stays connected and silent from here: nothing renews the window, so it runs out.
        clock.advanceMinutes(29)
        assertFalse(watchdog.isIdle())
        clock.advanceMinutes(1)
        assertTrue(watchdog.isIdle())
    }

    @Test
    fun trafficIntoAFullRetentionBufferStillCountsAsCapturing() {
        val clock = TestClock()
        val exchanges = MutableStateFlow(listOf(exchange("first")))
        val capture = DaemonCaptureActivity(exchanges, MutableStateFlow(emptyList())) { clock.now }
        val opened = capture.lastCaptureAtMillis()

        clock.advanceMinutes(5)
        exchanges.value = listOf(exchange("second"))

        assertTrue(capture.lastCaptureAtMillis() > opened)
    }

    @Test
    fun aZeroLingerDisablesTheExitEntirely() {
        val clock = TestClock()
        val watchdog = watchdog(clock, lingerMinutes = 0, referenced = { false })

        clock.advanceMinutes(10_000)

        assertFalse(watchdog.enabled)
        assertFalse(watchdog.isIdle())
    }

    private fun watchdog(
        clock: TestClock,
        lingerMinutes: Int = DEFAULT_IDLE_LINGER_MINUTES,
        referenced: () -> Boolean = { false },
        lastActivity: () -> Long = { 0L },
    ) = DaemonIdleWatchdog(
        lingerMillis = lingerMinutes * 60_000L,
        referenced = referenced,
        lastActivityAtMillis = lastActivity,
        onIdle = {},
        clock = { clock.now },
    )

    private fun exchange(id: String) = CapturedExchange(
        deviceName = "iPhone",
        appId = "com.example.app",
        platform = "ios",
        exchange = HttpExchange(id = id),
    )

    private class TestClock {
        var now = 0L
            private set

        fun advanceMinutes(minutes: Long) {
            now += minutes * 60_000
        }
    }
}
