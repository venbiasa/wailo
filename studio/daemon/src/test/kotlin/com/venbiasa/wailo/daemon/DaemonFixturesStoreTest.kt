package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostMapLocalRule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonFixturesStoreTest {
    @Test
    fun mapLocalRoundTripsRulesLayoutAndBody() {
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
                    layout = "R|login|1|pattern",
                    rules = listOf(rule.toDto()),
                ),
            )

            val loaded = DaemonFixturesStore(directory).loadMapLocal()
            assertTrue(Files.isRegularFile(directory.resolve("map-local.json")))
            assertEquals(false, loaded.enabled)
            assertEquals("R|login|1|pattern", loaded.layout)
            assertEquals("login", loaded.rules.single().id)
            assertEquals("""{"ok":true}""", loaded.rules.single().toDomain().bodyCopy().decodeToString())
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
                    allowlistEnabled = true,
                    allowPatterns = listOf("*.example.com"),
                    blocklistEnabled = false,
                    blockPatterns = emptyList(),
                ),
            )
            val loaded = DaemonFixturesStore(directory).loadCaptureFilter()
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
