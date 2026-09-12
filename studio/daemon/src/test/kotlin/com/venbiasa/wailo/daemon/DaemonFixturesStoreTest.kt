package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostMapLocalRule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonFixturesStoreTest {
    @Test
    fun scriptsRoundTripSourceGroupsAndMaster() {
        val directory = Files.createTempDirectory("wailo-scripts")
        try {
            val source = "function onRequest({ request }) { return request; }"
            val stored = PersistedScripts(
                enabled = false,
                nodes = listOf(
                    DaemonRuleNode(
                        group = DaemonRuleGroup("g1", "Auth", enabled = true),
                        rules = listOf(
                            ScriptRuleDto(
                                id = "rewrite",
                                enabled = true,
                                urlPattern = "https://example.com/*",
                                source = source,
                                onRequest = true,
                            ),
                        ),
                    ),
                ),
            )
            DaemonFixturesStore(directory).saveScripts(stored)

            val loaded = DaemonFixturesStore(directory).loadScriptsIfPresent()
            assertEquals(stored, loaded)
            assertTrue(Files.readString(directory.resolve("scripts.json")).contains(source))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

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
                delayMillis = 600,
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
            assertEquals(600, node.rules.single().delayMillis)
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

    /**
     * A rule persisted before ADR-0087 could name several methods. Collapsing last-wins on load is the
     * rewrite Studio already performed at the next edit; what must not happen is the field being ignored,
     * because a GET-only rule read back as "any method" starts answering calls it never matched.
     */
    @Test
    fun aRulePersistedWithSeveralMethodsCollapsesToTheLastOne() {
        val directory = Files.createTempDirectory("wailo-fixtures-methods")
        try {
            Files.writeString(
                directory.resolve("map-local.json"),
                """
                {"enabled":true,"nodes":[{"rules":[{"id":"login","enabled":true,
                "urlPattern":"https://example.com/login","methods":["GET","POST"],
                "statusCode":200,"headers":[]}]}]}
                """.trimIndent(),
            )

            val loaded = DaemonFixturesStore(directory).loadMapLocal().resolvedNodes()
            // Narrowed in the copy the daemon holds, not just on the way to the host: a poll carrying a
            // blank scalar beside a list nobody reads would show up in a frontend as "any method".
            assertEquals("POST", loaded.single().rules.single().method)
            assertEquals(null, loaded.single().rules.single().methods)
            assertEquals("POST", loaded.single().rules.single().toDomain().method)

            // And the next write states the one method and drops the old field for good.
            DaemonFixturesStore(directory).saveMapLocal(PersistedMapLocal(enabled = true, nodes = loaded))
            val rewritten = Files.readString(directory.resolve("map-local.json"))
            assertTrue(rewritten.contains(""""method":"POST""""))
            assertFalse(rewritten.contains("methods"))
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
