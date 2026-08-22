package com.venbiasa.wailo.daemon

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The takeover has to be undoable by a process that did not perform it, because the failure that
 * matters is the one the daemon does not survive (ADR-0075). These exercise the record rather than
 * `networksetup` itself: a test that reconfigured the machine running it would be the exact accident
 * the persistence exists to clean up.
 */
class SystemProxyControllerTest {
    private val directory = Files.createTempDirectory("wailo-system-proxy")
    private val statePath = directory.resolve("system-proxy.json")

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun applyingOnAMachineWithoutNetworksetupChangesNothingAndSaysWhy() {
        val controller = SystemProxyController(statePath)

        val applied = controller.apply(9090)

        assertEquals(SystemProxyController.isSupported, applied.supported)
        if (!applied.supported) {
            assertFalse(applied.active)
            assertTrue(applied.error != null)
            assertFalse(Files.exists(statePath), "nothing changed, so there is nothing to undo")
        }
    }

    @Test
    fun aStrandedSnapshotIsActedOnAndKeptUntilItFullyLands() {
        // A service no machine has, so this never reconfigures the network of whatever is running the
        // suite — which would be the very accident the persistence exists to clean up.
        Files.writeString(
            statePath,
            """[{"service":"$ABSENT_SERVICE","web":{"enabled":true,"server":"proxy.invalid","port":3128},""" +
                """"secure":{"enabled":false,"server":"","port":0}}]""",
        )

        val recovered = SystemProxyController(statePath).recover()

        assertTrue(recovered, "a record left behind must be acted on, not ignored")
        // The restore cannot succeed against a service that does not exist, and the record survives so
        // the next start tries again. Losing it would strand the machine for good.
        assertTrue(Files.exists(statePath), "an incomplete restore must stay recorded")
    }

    @Test
    fun recoveryIsAQuietNoOpWhenNothingWasStranded() {
        assertFalse(SystemProxyController(statePath).recover())
        assertFalse(SystemProxyController(directory.resolve("nope.json")).recover())
    }

    @Test
    fun restoringWithoutApplyingIsSafe() {
        val controller = SystemProxyController(statePath)

        val state = controller.restore()

        assertFalse(state.active)
        assertEquals(null, state.error)
    }

    private companion object {
        const val ABSENT_SERVICE = "Wailo Test Service That Does Not Exist"
    }
}
