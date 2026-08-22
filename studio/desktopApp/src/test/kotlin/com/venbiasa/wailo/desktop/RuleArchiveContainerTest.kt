package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.ArchivedSection
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.MapLocalGroup
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.RuleArchive
import com.venbiasa.wailo.shared.RuleArchiveCodec
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.toArchived
import com.venbiasa.wailo.shared.toArchivedNodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The container half of export/import. These exercise the zip directly on hand-built contents, so they
 * never touch the app data directory — reading the body stores is [buildArchive]'s job, not this file's.
 */
class RuleArchiveContainerTest {

    private val temp: File = Files.createTempDirectory("wailo-archive-test").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun rule(id: String, contentType: String = "application/json") = MapLocalRuleDef(
        id = id,
        name = id,
        urlPattern = "https://api.example.com/$id",
        statusCode = 200,
        headers = listOf(ResponseHeader("Content-Type", contentType)),
        inline = true,
    )

    private fun contents(
        nodes: List<MapLocalNode>,
        bodies: Map<String, ByteArray> = emptyMap(),
        entryOf: (String) -> String = { "" },
    ) = ArchiveContents(
        archive = RuleArchive(
            exportedAt = "2026-08-23T00:00:00Z",
            mapLocal = ArchivedSection(nodes = nodes.toArchivedNodes { it.toArchived(entryOf(it.id)) }),
        ),
        bodies = bodies,
    )

    private fun archiveFile(name: String) = File(temp, "$name.${RuleArchiveCodec.FILE_EXTENSION}")

    @Test
    fun manifestAndBodiesSurviveTheContainerRoundTrip() {
        // A PNG header: an image mock's bytes are not valid UTF-8, and keeping them as their own entry
        // rather than encoding them into the manifest is the whole point of the container.
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, -1)
        val json = """{"ok":true}""".toByteArray()
        val written = contents(
            nodes = listOf(
                RuleNode(rule("r1")),
                GroupNode(MapLocalGroup("g1", "Checkout"), listOf(rule("r2", "image/png"))),
            ),
            bodies = mapOf("bodies/map-local/r1.json" to json, "bodies/map-local/r2.png" to png),
            entryOf = { id -> if (id == "r1") "bodies/map-local/r1.json" else "bodies/map-local/r2.png" },
        )
        val target = archiveFile("rules")

        writeArchiveContainer(target, written)
        val read = readArchiveContainer(target)

        assertNotNull(read)
        assertEquals(written, read)
        assertContentEquals(png, read.bodies["bodies/map-local/r2.png"])
        assertEquals(listOf("r1", "r2"), read.archive.mapLocal!!.nodes.flatMap { it.rules }.map { it.id })
    }

    @Test
    fun theContainerIsAZipAnyoneCanOpen() {
        val target = archiveFile("rules")
        writeArchiveContainer(
            target,
            contents(
                nodes = listOf(RuleNode(rule("r1"))),
                bodies = mapOf("bodies/map-local/r1.json" to """{"ok":true}""".toByteArray()),
                entryOf = { "bodies/map-local/r1.json" },
            ),
        )

        // Being readable by an ordinary zip tool is a feature, not an implementation detail: it is how
        // someone who is sent an archive inspects what it will serve before importing it.
        ZipFile(target).use { zip ->
            assertEquals(
                listOf(RuleArchiveCodec.MANIFEST_ENTRY, "bodies/map-local/r1.json"),
                zip.entries().toList().map { it.name },
            )
            val manifest = zip.getInputStream(zip.getEntry(RuleArchiveCodec.MANIFEST_ENTRY)).use {
                it.readBytes().decodeToString()
            }
            assertTrue(manifest.contains("\"id\": \"r1\""), "manifest should be readable JSON, was:\n$manifest")
        }
    }

    @Test
    fun exportingTheSameRulesTwiceProducesIdenticalBytes() {
        val source = contents(
            nodes = listOf(RuleNode(rule("r1")), RuleNode(rule("r2"))),
            bodies = mapOf(
                "bodies/map-local/r2.json" to "b".toByteArray(),
                "bodies/map-local/r1.json" to "a".toByteArray(),
            ),
            entryOf = { id -> "bodies/map-local/$id.json" },
        )
        val first = archiveFile("first").also { writeArchiveContainer(it, source) }
        val second = archiveFile("second").also { writeArchiveContainer(it, source) }

        // Stable output is what lets a user keep archives in version control and see only real changes.
        assertContentEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun readingRejectsAFileThatIsNotOneOfOurs() {
        assertNull(readArchiveContainer(File(temp, "notes.txt").apply { writeText("just some text") }))
        assertNull(readArchiveContainer(archiveFile("missing")))

        // A zip without our manifest is somebody else's archive, and importing it would be nonsense.
        val strayZip = File(temp, "other.zip")
        ZipOutputStream(strayZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a Wailo archive".toByteArray())
            zip.closeEntry()
        }
        assertNull(readArchiveContainer(strayZip))
    }

    @Test
    fun aBodyEntryTheManifestNeverNamesIsIgnored() {
        val target = archiveFile("rules")
        writeArchiveContainer(
            target,
            contents(
                nodes = listOf(RuleNode(rule("r1"))),
                // Named by no rule, so an importer has no reason to read it back into memory.
                bodies = mapOf("bodies/map-local/orphan.json" to "{}".toByteArray()),
            ),
        )

        val read = readArchiveContainer(target)
        assertNotNull(read)
        assertTrue(read.bodies.isEmpty())
        assertEquals(listOf("r1"), read.archive.mapLocal!!.nodes.flatMap { it.rules }.map { it.id })
    }

    @Test
    fun entryNamesAreSanitizedSoAnIdCannotEscapeTheBodiesFolder() {
        // Seed ids arrive as free text from the CLI and MCP, so a traversal attempt has to be defused
        // when the archive is written rather than trusted when it is read.
        val hostile = bodyEntryName("seeds", "../../../../etc/passwd", "json", emptySet())
        val leaf = hostile.removePrefix("${RuleArchiveCodec.BODIES_PREFIX}seeds/")

        assertTrue(hostile.startsWith("${RuleArchiveCodec.BODIES_PREFIX}seeds/"), hostile)
        assertTrue("/" !in leaf, hostile)
        assertTrue(".." !in hostile, hostile)
    }

    @Test
    fun twoIdsThatSanitizeAlikeStillGetTheirOwnEntry() {
        val first = bodyEntryName("seeds", "a/b", "json", emptySet())
        val second = bodyEntryName("seeds", "a:b", "json", setOf(first))

        assertEquals("bodies/seeds/a_b.json", first)
        assertTrue(second != first, "a collision would make one body overwrite the other")
    }

    @Test
    fun aRuleWithNoBodyNamesNoEntry() {
        val target = archiveFile("rules")
        writeArchiveContainer(target, contents(nodes = listOf(RuleNode(rule("r1")))))

        val read = readArchiveContainer(target)
        assertNotNull(read)
        assertEquals("", read.archive.mapLocal!!.nodes.flatMap { it.rules }.single().bodyEntry)
        assertTrue(read.bodies.isEmpty())
    }
}
