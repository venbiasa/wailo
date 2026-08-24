package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostMapLocalRule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonFixturesStoreTest {
    /**
     * The layout file holds the body's *reference*, never its bytes (ADR-0086) — that is what keeps a
     * reorder or a toggle from rewriting every fixture in the file.
     */
    @Test
    fun mapLocalRoundTripsGroupsRulesAndTheBodyReferenceButNotTheBytes() {
        val directory = Files.createTempDirectory("wailo-fixtures")
        try {
            val store = DaemonFixturesStore(directory)
            val body = """{"ok":true}""".toByteArray()
            val rule = HostMapLocalRule(
                id = "login",
                urlPattern = "https://example.com/login",
                bodySize = body.size,
                bodyHash = bodyDigest(body),
            )
            store.saveMapLocal(
                PersistedMapLocal(
                    enabled = false,
                    nodes = listOf(
                        DaemonRuleNode(DaemonRuleGroup("g1", "Checkout", enabled = false), listOf(rule.toDto())),
                    ),
                ),
            )

            val file = directory.resolve("map-local.json")
            val loaded = DaemonFixturesStore(directory).loadMapLocal()
            assertTrue(Files.isRegularFile(file))
            assertEquals(false, loaded.enabled)
            val node = loaded.resolvedNodes().single()
            assertEquals(DaemonRuleGroup("g1", "Checkout", enabled = false), node.group)
            assertEquals("login", node.rules.single().id)
            assertEquals(body.size, node.rules.single().bodySize)
            assertEquals(bodyDigest(body), node.rules.single().bodyHash)
            assertFalse(Files.readString(file).contains(body.encodeBase64()))
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
