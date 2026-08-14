package com.venbiasa.wailo.daemon.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbClientTest {
    @Test
    fun readsReadyAndUnauthorizedDevicesWithoutDroppingEither() {
        val devices = parseDevices(
            """
            List of devices attached
            R5CT10ABCDE device usb:1 model:SM_A525F transport_id:2
            1234567890 unauthorized
            """.trimIndent(),
        )

        assertEquals(listOf("R5CT10ABCDE", "1234567890"), devices.map { it.serial })
        assertEquals("SM A525F", devices[0].label)
        assertTrue(devices[0].ready)
        assertFalse(devices[1].ready)
        assertEquals("1234567890", devices[1].label)
    }

    @Test
    fun ignoresColdStartChatterAndKeepsUnusualStates() {
        val devices = parseDevices(
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554 offline
            0A1B2C3D recovery
            """.trimIndent(),
        )

        assertEquals(listOf("offline", "recovery"), devices.map { it.state })
        assertTrue(devices.none { it.ready })
    }

    @Test
    fun emptyOutputProducesNoDevices() {
        assertTrue(parseDevices("").isEmpty())
        assertTrue(parseDevices("List of devices attached\n\n").isEmpty())
    }
}
