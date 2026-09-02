package com.venbiasa.wailo.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFileTest {
    @Test
    fun replacesAStateFileWithoutLeavingATemporarySibling() {
        val directory = Files.createTempDirectory("wailo-atomic-file")
        try {
            val target = directory.resolve("state.json")

            assertTrue(writeAtomically(target, "first"))
            assertTrue(writeAtomically(target, "second"))

            assertEquals("second", Files.readString(target))
            Files.list(directory).use { entries ->
                assertFalse(entries.anyMatch { it.fileName.toString().endsWith(".tmp") })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
