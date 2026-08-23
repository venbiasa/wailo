package com.venbiasa.wailo.daemon

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The takeover has to be undoable by a process that did not perform it, because the failure that
 * matters is the one the daemon does not survive (ADR-0075). Every case here drives a stand-in for
 * `networksetup`, so the outcome is the same on any host and the suite never reconfigures the machine
 * running it — that accident is the one the persistence exists to clean up.
 */
class SystemProxyControllerTest {
    private val directory = Files.createTempDirectory("wailo-system-proxy")
    private val statePath = directory.resolve("system-proxy.json")

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun applyingWithNoMachineToDriveChangesNothingAndSaysWhy() {
        val controller = SystemProxyController.over(machine = null, statePath = statePath)

        val applied = controller.apply(9090)

        assertFalse(applied.supported)
        assertFalse(applied.active)
        assertNotNull(applied.error, "a refusal has to say what is missing")
        assertFalse(Files.exists(statePath), "nothing changed, so there is nothing to undo")
    }

    @Test
    fun aStrandedSnapshotIsActedOnAndKeptUntilItFullyLands() {
        Files.writeString(
            statePath,
            """[{"service":"Ethernet","web":{"enabled":true,"server":"proxy.internal","port":3128},""" +
                """"secure":{"enabled":false,"server":"","port":0}}]""",
        )
        // The machine that came back is not the one the record describes, so the restore cannot land.
        val machine = FakeMachine("Wi-Fi")

        val recovered = SystemProxyController.over(machine, statePath).recover()

        assertTrue(recovered, "a record left behind must be acted on, not ignored")
        // It survives so the next start tries again; losing it would strand the machine for good.
        assertTrue(Files.exists(statePath), "an incomplete restore must stay recorded")
    }

    @Test
    fun recoveryIsAQuietNoOpWhenNothingWasStranded() {
        val machine = FakeMachine("Wi-Fi")

        assertFalse(SystemProxyController.over(machine, statePath).recover())
        assertFalse(SystemProxyController.over(machine, directory.resolve("nope.json")).recover())
    }

    @Test
    fun restoringWithoutApplyingIsSafe() {
        val controller = SystemProxyController.over(FakeMachine("Wi-Fi"), statePath)

        val state = controller.restore()

        assertFalse(state.active)
        assertEquals(null, state.error)
    }
}
