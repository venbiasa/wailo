package com.venbiasa.wailo.sdk.android.panel

import com.venbiasa.wailo.sdk.android.WailoConnectionPhase
import com.venbiasa.wailo.sdk.android.WailoDesktop
import com.venbiasa.wailo.sdk.android.WailoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's wording and its Connect button, which are the two places it says something the SDK did not.
 *
 * No client is running under these, so the `Wailo.*` calls the actions make are no-ops — which is the
 * point: what is being pinned is what the panel concludes from a [WailoStatus], not what the SDK does
 * with it.
 */
class WailoPanelModelTest {

    private fun model(status: WailoStatus = WailoStatus()) = WailoPanelModel(status).also { it.onStatus(status) }

    @Test
    fun `a waived handshake is named as the cable, and never reported as paired`() {
        val model = model(
            WailoStatus(
                connected = true,
                activeAddress = "localhost:9099",
                handshakeWaived = true,
                phase = WailoConnectionPhase.CONNECTED,
            ),
        )

        assertEquals("Connected", model.statusTitle)
        assertEquals("adb", model.transportLabel)
        assertTrue(model.statusDetail.contains("adb reverse"))
        // The loopback link is authenticated by the cable, not by a key — saying "Paired" here would
        // claim something the handshake never proved.
        assertFalse(model.isPairedConnection)
    }

    @Test
    fun `a Wi-Fi link is only called paired once a live pairing backs it`() {
        val overWifi = WailoStatus(connected = true, activeAddress = "10.0.0.2:9099", configuredHost = "10.0.0.2")

        assertEquals("Wi-Fi", model(overWifi).transportLabel)
        assertFalse(model(overWifi).isPairedConnection)
    }

    @Test
    fun `nothing dialled reads as not started rather than as a failure`() {
        val model = model()

        assertEquals("Not started", model.statusTitle)
        assertNull(model.transportLabel)
        assertTrue(model.statusDetail.contains("Wailo.webSocketSink"))
    }

    @Test
    fun `a stopped client keeps the authenticated refusal or identity mismatch explanation`() {
        val mismatch = model(WailoStatus(phase = WailoConnectionPhase.IDENTITY_MISMATCH))
        assertEquals("Identity mismatch", mismatch.statusTitle)
        assertTrue(mismatch.statusDetail.contains("different Studio"))

        val refusal = model(
            WailoStatus(
                phase = WailoConnectionPhase.REFUSED,
                refusal = "Only paired devices may connect.",
            ),
        )
        assertEquals("Refused", refusal.statusTitle)
        assertEquals("Only paired devices may connect.", refusal.statusDetail)
    }

    @Test
    fun `looking for a desktop says what it needs, so an empty list is not read as broken`() {
        val model = model(WailoStatus(connected = false, activeAddress = "localhost:9099"))

        assertEquals("Not connected", model.statusTitle)
        assertTrue(model.statusDetail.contains("same Wi-Fi"))
    }

    @Test
    fun `a port typed into the address field moves into the port field`() {
        val model = model()
        model.editHost("10.0.0.2:8899")

        model.apply()

        assertEquals("10.0.0.2", model.host)
        assertEquals("8899", model.port)
        assertEquals(ConnectAttempt.Dialling("10.0.0.2"), model.attempt)
    }

    @Test
    fun `an address that cannot be dialled is refused with a reason instead of being stored`() {
        val model = model()
        model.editHost("10.0.0.2:8080:9000")

        model.apply()

        assertNotNull(model.hostError)
        assertEquals(ConnectAttempt.Idle, model.attempt)
        // The typed text is left alone: rewriting the field under someone mid-correction is worse than
        // the error message.
        assertEquals("10.0.0.2:8080:9000", model.host)
    }

    @Test
    fun `an out-of-range port is caught on submit, not while typing`() {
        val model = model()
        model.editHost("10.0.0.2")
        model.editPort("99999")
        assertNull(model.portError)

        model.apply()

        assertNotNull(model.portError)
        model.editPort("9099")
        assertNull(model.portError)
    }

    @Test
    fun `Connect stays off for an address already being dialled`() {
        val model = model(WailoStatus(configuredHost = "10.0.0.2", configuredPort = 9099))

        assertFalse(model.canApply)
        model.editPort("8899")
        assertTrue(model.canApply)
    }

    @Test
    fun `an attempt settles from the SDK, so a dropped link stops claiming to be connected`() {
        val model = model()
        model.editHost("10.0.0.2")
        model.apply()
        assertEquals(ConnectAttempt.Dialling("10.0.0.2"), model.attempt)

        model.onStatus(WailoStatus(connected = true, activeAddress = "10.0.0.2:9099"))
        assertEquals(ConnectAttempt.Connected("10.0.0.2"), model.attempt)

        model.onStatus(WailoStatus(connected = false, activeAddress = "10.0.0.2:9099"))
        assertEquals(ConnectAttempt.Stopped("10.0.0.2"), model.attempt)
    }

    @Test
    fun `an attempt the client refused to open at all is reported as stopped`() {
        val model = model()
        model.editHost("10.0.0.2")
        model.apply()

        // No active address means the connect loop decided against dialling — an identity change, or a
        // refusal it will not walk back into.
        model.onStatus(WailoStatus(connected = false, activeAddress = null))

        assertEquals(ConnectAttempt.Stopped("10.0.0.2"), model.attempt)
    }

    @Test
    fun `the code field only ever holds characters the handshake can derive from`() {
        val model = model()

        model.editPairingCode("k7-il o0u9")

        // Lowercase raised, separators dropped, and I/L/O/U — the four Crockford excludes — gone, so a
        // typed O can never stretch into a silently wrong key.
        assertEquals("K709", model.pairingCode)
        assertFalse(model.canPairWithCode)
    }

    @Test
    fun `a code needs a desktop to aim it at`() {
        val target = WailoDesktop(name = "a dev's Mac", host = "10.0.0.2", port = 9099, studioId = "sid-a")
        val model = model(WailoStatus(discovered = listOf(target)))
        model.editPairingCode("0123456789")

        assertEquals(target, model.codeTarget)
        assertTrue(model.canPairWithCode)

        // A desktop too old to advertise a fingerprint has nothing to derive a key against.
        model.onStatus(WailoStatus(discovered = listOf(target.copy(studioId = ""))))
        assertNull(model.codeTarget)
        assertFalse(model.canPairWithCode)
        assertTrue(model.pairableDesktops.isEmpty())
    }
}
