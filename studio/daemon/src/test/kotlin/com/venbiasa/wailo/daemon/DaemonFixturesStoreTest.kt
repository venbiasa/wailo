package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostMapLocalRule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonFixturesStoreTest {
    @Test
    fun mapLocalRoundTripsGroupsRulesAndBody() {
        val directory = Files.createTempDirectory("wailo-fixtures")
        try {
            val store = DaemonFixturesStore(directory)
            val rule = HostMapLocalRule(
                id = "login",
                urlPattern = "https://example.com/login",
                body = """{"ok":true}""".toByteArray(),
            )
            store.saveMapLocal(
                PersistedMapLocal(
                    enabled = false,
                    nodes = listOf(
                        DaemonRuleNode(DaemonRuleGroup("g1", "Checkout", enabled = false), listOf(rule.toDto())),
                    ),
                ),
            )

            val loaded = DaemonFixturesStore(directory).loadMapLocal()
            assertTrue(Files.isRegularFile(directory.resolve("map-local.json")))
            assertEquals(false, loaded.enabled)
            val node = loaded.resolvedNodes().single()
            assertEquals(DaemonRuleGroup("g1", "Checkout", enabled = false), node.group)
            assertEquals("login", node.rules.single().id)
            assertEquals("""{"ok":true}""", node.rules.single().toDomain().bodyCopy().decodeToString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * The upgrade path off ADR-0061's flat-list-plus-blob file. Reading it as an empty configuration
     * would look like "no rules" and be persisted as such on the next write, so the rules have to
     * survive even though the grouping in the blob cannot.
     */
    @Test
    fun aPreGroupsFileStillLoadsItsRules() {
        val directory = Files.createTempDirectory("wailo-fixtures-legacy")
        try {
            val rule = HostMapLocalRule(id = "login", urlPattern = "https://example.com/login")
            val legacy = PersistedMapLocal(enabled = true, rules = listOf(rule.toDto()))
            Files.writeString(
                directory.resolve("map-local.json"),
                DaemonJson.encodeToString(PersistedMapLocal.serializer(), legacy),
            )

            val loaded = DaemonFixturesStore(directory).loadMapLocal()
            val node = loaded.resolvedNodes().single()
            assertEquals(null, node.group)
            assertEquals("login", node.rules.single().id)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun captureFilterRoundTripsPatternsAndSwitches() {
        val directory = Files.createTempDirectory("wailo-fixtures-filter")
        try {
            DaemonFixturesStore(directory).saveCaptureFilter(
                PersistedCaptureFilter(
                    masterEnabled = false,
                    allowlistEnabled = true,
                    allowPatterns = listOf("*.example.com"),
                    blocklistEnabled = false,
                    blockPatterns = emptyList(),
                ),
            )
            val loaded = DaemonFixturesStore(directory).loadCaptureFilter()
            // The master and the list's armed state are stored apart, so an off master reads back as a
            // paused filter rather than an empty one (ADR-0082).
            assertEquals(false, loaded.masterEnabled)
            assertEquals(true, loaded.allowlistEnabled)
            assertEquals(listOf("*.example.com"), loaded.allowPatterns)
            val empty = Files.createTempDirectory("wailo-fixtures-missing")
            try {
                assertEquals(null, DaemonFixturesStore(empty).loadCaptureFilterIfPresent())
            } finally {
                empty.toFile().deleteRecursively()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aMissingOrCorruptFileLoadsAsEmpty() {
        val directory = Files.createTempDirectory("wailo-fixtures-empty")
        try {
            val missing = DaemonFixturesStore(directory).loadMapLocal()
            assertEquals(PersistedMapLocal(), missing)

            Files.writeString(directory.resolve("map-local.json"), "{not-json")
            val corrupt = DaemonFixturesStore(directory).loadMapLocal()
            assertEquals(PersistedMapLocal(), corrupt)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
