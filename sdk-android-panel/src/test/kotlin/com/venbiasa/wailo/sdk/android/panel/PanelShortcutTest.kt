package com.venbiasa.wailo.sdk.android.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which icon the dynamic shortcut attaches to. The panel declares a launcher entry of its own, so the
 * host's entry has to be picked out of a list that contains both.
 */
class PanelShortcutTest {

    private val panel = "com.venbiasa.wailo.sdk.android.panel.WailoPanelActivity"

    @Test
    fun `the host's launcher entry is chosen over the panel's own`() {
        assertEquals(
            "com.example.host.MainActivity",
            hostLauncherClass(listOf(panel, "com.example.host.MainActivity"), panel),
        )
    }

    @Test
    fun `the first host entry wins when an app declares several`() {
        assertEquals(
            "com.example.Main",
            hostLauncherClass(listOf("com.example.Main", "com.example.Kiosk", panel), panel),
        )
    }

    @Test
    fun `an app with no launcher entry of its own gets no shortcut`() {
        assertNull(hostLauncherClass(listOf(panel), panel))
        assertNull(hostLauncherClass(emptyList(), panel))
    }
}
