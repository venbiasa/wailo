package com.venbiasa.wailo.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The snapshot's one job is to describe the machine *before* Wailo touched it, so the rule that matters
 * is that Wailo's own listener can never be mistaken for that (ADR-0078). The rest of the controller
 * drives `networksetup`, which a unit test must not do — it would reconfigure the machine running it.
 */
class SystemProxySettingTest {
    @Test
    fun ourOwnListenerIsNeverRecordedAsAPriorSetting() {
        val leftover = ProxySetting(enabled = true, server = "127.0.0.1", port = 9090)

        assertTrue(leftover.ours(9090))
        assertFalse(leftover.unlessOurs(9090).configured)
        // Recording it would also chain the relay to itself, which is a loop rather than a route out.
        assertNull(leftover.unlessOurs(9090).upstream())
    }

    @Test
    fun everySpellingOfThisMachineCounts() {
        listOf("127.0.0.1", "::1", "localhost").forEach { name ->
            assertTrue(ProxySetting(true, name, 9090).ours(9090), "$name is this machine")
        }
    }

    @Test
    fun aRealUpstreamSurvivesAndStaysChainable() {
        val corporate = ProxySetting(enabled = true, server = "proxy.internal", port = 8080)

        assertFalse(corporate.ours(9090))
        assertEquals(corporate, corporate.unlessOurs(9090))
        assertEquals("proxy.internal", corporate.upstream()?.host)
    }

    @Test
    fun aLoopbackProxyOnSomeOtherPortIsSomebodyElsesAndIsKept() {
        // Another local proxy is a setting the user chose; only our own port is ours to discard.
        val other = ProxySetting(enabled = true, server = "127.0.0.1", port = 8888)

        assertFalse(other.ours(9090))
        assertEquals(8888, other.unlessOurs(9090).port)
    }

    @Test
    fun aDisabledLeftoverIsNotTreatedAsATakeover() {
        // Nothing routes through it, so there is nothing to undo and nothing to warn about.
        assertFalse(ProxySetting(enabled = false, server = "127.0.0.1", port = 9090).ours(9090))
    }
}
