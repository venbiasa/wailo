package com.venbiasa.wailo.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store behind ADR-0086's references. Everything here is about the bytes outliving — or not
 * outliving — the layout that names them, since that is the failure the inline body could not have.
 */
class DaemonRuleBodyStoreTest {
    private fun <T> withStore(block: (Path, DaemonRuleBodyStore) -> T): T {
        val directory = Files.createTempDirectory("wailo-rule-bodies")
        return try {
            block(directory, DaemonRuleBodyStore(directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aBodySurvivesTheProcessThatWroteIt() = withStore { directory, store ->
        val bytes = """{"ok":true}""".toByteArray()
        store.put(RULE_FAMILY_MAP_LOCAL, "login", bytes)

        val reopened = DaemonRuleBodyStore(directory)
        assertContentEquals(ByteArray(0), reopened.body(RULE_FAMILY_MAP_LOCAL, "login"))
        reopened.load(RULE_FAMILY_MAP_LOCAL, listOf("login"))
        assertContentEquals(bytes, reopened.body(RULE_FAMILY_MAP_LOCAL, "login"))
    }

    /** Two families, one id: a seed made from a fixture keeps the rule's id, so they must not collide. */
    @Test
    fun theSameIdInTwoFamiliesIsTwoBodies() = withStore { _, store ->
        store.put(RULE_FAMILY_MAP_LOCAL, "shared", "fixture".toByteArray())
        store.put(RULE_FAMILY_SEEDS, "shared", "seed".toByteArray())

        assertEquals("fixture", store.body(RULE_FAMILY_MAP_LOCAL, "shared").decodeToString())
        assertEquals("seed", store.body(RULE_FAMILY_SEEDS, "shared").decodeToString())
    }

    /**
     * An id is a caller's string — MCP and the CLI both mint them — so it is digested into a file name
     * rather than spelled out. A traversing id must land inside the family directory like any other.
     */
    @Test
    fun anIdCannotAddressAPathOutsideTheStore() = withStore { directory, store ->
        store.put(RULE_FAMILY_MAP_LOCAL, "../../escape", "nope".toByteArray())

        assertEquals("nope", store.body(RULE_FAMILY_MAP_LOCAL, "../../escape").decodeToString())
        assertTrue(Files.notExists(directory.resolve("escape")))
        assertEquals(1, Files.list(directory.resolve("rule-bodies").resolve(RULE_FAMILY_MAP_LOCAL)).use { it.count() }.toInt())
    }

    /** Authoring a body away is a delete, not a zero-length file that would read back as "still there". */
    @Test
    fun clearingABodyRemovesIt() = withStore { directory, store ->
        store.put(RULE_FAMILY_SEEDS, "poll", "canned".toByteArray())
        store.put(RULE_FAMILY_SEEDS, "poll", ByteArray(0))

        assertContentEquals(ByteArray(0), store.body(RULE_FAMILY_SEEDS, "poll"))
        assertEquals(0, Files.list(directory.resolve("rule-bodies").resolve(RULE_FAMILY_SEEDS)).use { it.count() }.toInt())
    }

    /**
     * The sweep is what keeps a deleted rule from leaving its fixture on disk forever — and what
     * collects one written by a daemon that died before the layout naming it was persisted.
     */
    @Test
    fun aSweepDropsTheBodiesNoRuleClaims() = withStore { directory, store ->
        store.put(RULE_FAMILY_MAP_LOCAL, "kept", "keep".toByteArray())
        store.put(RULE_FAMILY_MAP_LOCAL, "orphan", "drop".toByteArray())

        store.retain(RULE_FAMILY_MAP_LOCAL, listOf("kept"))

        assertEquals("keep", store.body(RULE_FAMILY_MAP_LOCAL, "kept").decodeToString())
        assertContentEquals(ByteArray(0), store.body(RULE_FAMILY_MAP_LOCAL, "orphan"))
        assertEquals(1, Files.list(directory.resolve("rule-bodies").resolve(RULE_FAMILY_MAP_LOCAL)).use { it.count() }.toInt())
    }

    /** A sweep is scoped to the family it names, or publishing a fixture panel would erase the seeds. */
    @Test
    fun aSweepLeavesTheOtherFamilyAlone() = withStore { _, store ->
        store.put(RULE_FAMILY_MAP_LOCAL, "fixture", "keep".toByteArray())
        store.put(RULE_FAMILY_SEEDS, "poll", "keep".toByteArray())

        store.retain(RULE_FAMILY_MAP_LOCAL, emptyList())

        assertContentEquals(ByteArray(0), store.body(RULE_FAMILY_MAP_LOCAL, "fixture"))
        assertEquals("keep", store.body(RULE_FAMILY_SEEDS, "poll").decodeToString())
    }

    /**
     * The digest is the frontend's evidence that its bytes landed, so an empty body has to have no
     * digest at all — the hash of zero bytes would read as a body the daemon holds.
     */
    @Test
    fun anEmptyBodyHasNoDigest() {
        assertEquals("", bodyDigest(ByteArray(0)))
        assertEquals(bodyDigest("a".toByteArray()), bodyDigest("a".toByteArray()))
        assertTrue(bodyDigest("a".toByteArray()) != bodyDigest("b".toByteArray()))
    }
}
