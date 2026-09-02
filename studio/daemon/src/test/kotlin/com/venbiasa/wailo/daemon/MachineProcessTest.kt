package com.venbiasa.wailo.daemon

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineProcessTest {
    @Test
    fun returnsCombinedOutputOnlyForAZeroExit() {
        assertEquals(
            "out\nerr\n",
            runMachineProcess(listOf("/bin/sh", "-c", "printf 'out\\n'; printf 'err\\n' >&2"), 5),
        )
        assertNull(runMachineProcess(listOf("/bin/sh", "-c", "exit 7"), 5))
    }

    @Test
    fun terminatesACommandThatExceedsItsDeadline() {
        val elapsed = measureTimeMillis {
            assertNull(runMachineProcess(listOf("/bin/sh", "-c", "sleep 10"), 1))
        }

        assertTrue(elapsed < 5_000, "timed-out process took ${elapsed}ms to terminate")
    }

    @Test
    fun boundsOutputFromAMisbehavingTool() {
        val output = runMachineProcess(
            listOf("/bin/sh", "-c", "yes x | dd bs=1024 count=1024 2>/dev/null"),
            5,
        )

        assertTrue(output != null)
        assertTrue(output.length <= 256 * 1024)
    }
}
