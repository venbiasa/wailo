package com.venbiasa.wailo.desktop.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbClientTest {

    @Test
    fun `reads a serial, its state and its model`() {
        val devices = parseDevices(
            """
            List of devices attached
            R5CT10ABCDE            device usb:338690048X product:a52qxx model:SM_A525F device:a52q transport_id:2
            """.trimIndent(),
        )

        val device = devices.single()
        assertEquals("R5CT10ABCDE", device.serial)
        assertEquals("device", device.state)
        assertTrue(device.ready)
        // Underscores are how adb spells a model, not how anyone reads one.
        assertEquals("SM A525F", device.label)
    }

    @Test
    fun `an unauthorised device is reported, not dropped`() {
        // Two fields where a ready device has eight, and no model at all — it has not agreed to say.
        // Dropping it would leave a plugged-in phone missing from the panel with nothing explaining why.
        val device = parseDevices("List of devices attached\n1234567890\tunauthorized").single()

        assertEquals("1234567890", device.serial)
        assertEquals("unauthorized", device.state)
        assertFalse(device.ready)
        assertEquals("1234567890", device.label)
    }

    @Test
    fun `daemon chatter on a cold start is not read as a device`() {
        val devices = parseDevices(
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1

            """.trimIndent(),
        )

        assertEquals(listOf("emulator-5554"), devices.map(AdbDevice::serial))
    }

    @Test
    fun `an empty list is empty, header and trailing blank line included`() {
        assertTrue(parseDevices("List of devices attached\n\n").isEmpty())
        assertTrue(parseDevices("").isEmpty())
    }

    @Test
    fun `offline and recovery states are kept verbatim so the panel can say what adb said`() {
        val devices = parseDevices(
            """
            List of devices attached
            R5CT10ABCDE            offline
            0A1B2C3D               recovery
            """.trimIndent(),
        )

        assertEquals(listOf("offline", "recovery"), devices.map(AdbDevice::state))
        assertTrue(devices.none(AdbDevice::ready))
    }
}
