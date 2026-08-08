package com.venbiasa.wailo.sdk.android.panel

import com.venbiasa.wailo.sdk.android.WailoDesktop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged Connect list (ADR-0047), which is the panel's only real logic — everything else it draws is
 * a passthrough of `WailoStatus`.
 */
class PanelDesktopTest {

    private fun advertising(name: String, host: String, port: Int = 9099, studioId: String = "") =
        WailoDesktop(name = name, host = host, port = port, studioId = studioId)

    private fun remembered(
        studioId: String,
        lastHost: String = "10.0.0.2",
        trustedOnFirstUse: Boolean = false,
        refused: Boolean = false,
    ) = Remembered(studioId, lastHost, trustedOnFirstUse, refused)

    @Test
    fun `a desktop that is both advertising and remembered appears once`() {
        val merged = mergeDesktops(
            listOf(advertising("a dev's Mac", "10.0.0.2", studioId = "sid-a")),
            listOf(remembered("sid-a")),
        )

        assertEquals(1, merged.size)
        val desktop = merged.single()
        assertTrue(desktop.online)
        assertEquals("sid-a", desktop.fingerprint)
        assertEquals("Paired", desktop.trustLabel)
        assertEquals("10.0.0.2:9099", desktop.address)
    }

    @Test
    fun `a remembered desktop that is not advertising is still listed, so it can be forgotten`() {
        val merged = mergeDesktops(emptyList(), listOf(remembered("sid-a", lastHost = "10.0.0.9")))

        val desktop = merged.single()
        assertFalse(desktop.online)
        assertEquals("10.0.0.9", desktop.name)
        assertTrue(desktop.isRemembered)
        assertEquals("Paired — not on this network", desktop.trustLabel)
        // No port to offer, so Fill leaves the field empty and the default stays in force.
        assertNull(desktop.port)
        assertTrue(desktop.canFill)
    }

    @Test
    fun `an offline row's name is its last address, so the subtitle would only repeat it`() {
        val offline = mergeDesktops(emptyList(), listOf(remembered("sid-a", lastHost = "10.0.0.9"))).single()
        assertNull(offline.subtitle)

        val online = mergeDesktops(
            listOf(advertising("a dev's Mac", "10.0.0.2", studioId = "sid-a")),
            listOf(remembered("sid-a")),
        ).single()
        assertEquals("10.0.0.2:9099", online.subtitle)
    }

    @Test
    fun `advertising desktops come before remembered ones, which sort by name`() {
        val merged = mergeDesktops(
            listOf(advertising("zoe's Mac", "10.0.0.3", studioId = "sid-z")),
            listOf(remembered("sid-b", lastHost = "beta"), remembered("sid-a", lastHost = "alpha")),
        )

        assertEquals(listOf("zoe's Mac", "alpha", "beta"), merged.map { it.name })
    }

    @Test
    fun `a desktop too old to advertise a fingerprint never joins a pairing`() {
        val merged = mergeDesktops(
            listOf(advertising("old Mac", "10.0.0.4", studioId = "")),
            listOf(remembered("sid-a", lastHost = "10.0.0.4")),
        )

        // Same address, but nothing proves it is the same identity — so the pairing stays its own row
        // rather than lending its "Paired" badge to whoever happens to answer there.
        assertEquals(2, merged.size)
        assertNull(merged.first().pairing)
        assertNull(merged.first().trustLabel)
        assertEquals("10.0.0.4:9099", merged.first().id)
    }

    @Test
    fun `first contact and a refusal are both said out loud`() {
        val merged = mergeDesktops(
            listOf(advertising("a dev's Mac", "10.0.0.2", studioId = "sid-a")),
            listOf(remembered("sid-a", trustedOnFirstUse = true, refused = true)),
        )

        val desktop = merged.single()
        assertEquals("Trusted on first contact", desktop.trustLabel)
        assertEquals("This desktop no longer recognises this device.", desktop.warning)
    }
}
