package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.RuleArchive
import com.venbiasa.wailo.shared.RuleArchiveCodec
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reading and writing the archive container — the zip mechanics behind [RuleArchiveCodec]'s format.
 *
 * Kept free of the body stores so it can be tested on hand-built contents without an app data
 * directory; [buildArchive]/[importArchive] in `RuleArchiveIo` are the halves that talk to the stores.
 */

/** A whole archive in memory: the manifest plus the body bytes its rules name, keyed by entry name. */
data class ArchiveContents(
    val archive: RuleArchive,
    val bodies: Map<String, ByteArray>,
) {
    // Data-class equality on a ByteArray-valued map compares references, which would make any assertion
    // about a round trip pass for the wrong reason.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? ArchiveContents ?: return false
        if (archive != that.archive || bodies.keys != that.bodies.keys) return false
        return bodies.all { (name, bytes) -> that.bodies[name].contentEquals(bytes) }
    }

    override fun hashCode(): Int = 31 * archive.hashCode() + bodies.keys.hashCode()
}

/**
 * The entry name to store [id]'s body under, below [RuleArchiveCodec.BODIES_PREFIX]`/[tool]`.
 *
 * Rule ids reach us from the CLI and MCP as free text, so they are sanitized rather than trusted: an id
 * with a slash or a `..` in it would otherwise write a nested — or escaping — entry. [taken] carries the
 * names already used so two ids that sanitize alike still get their own entry.
 */
fun bodyEntryName(tool: String, id: String, extension: String, taken: Set<String>): String {
    // Dots go too, not just separators: leaving them would still allow a `..` segment, and the only dot
    // this name needs is the one in front of the extension, which is added below.
    val safeId = id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .ifBlank { "body" }
    val prefix = "${RuleArchiveCodec.BODIES_PREFIX}$tool/$safeId"
    val first = "$prefix.$extension"
    if (first !in taken) return first
    return generateSequence(2) { it + 1 }.map { "$prefix-$it.$extension" }.first { it !in taken }
}

/** Writes [contents] to [target] as the container. Throws on IO failure; callers report the reason. */
fun writeArchiveContainer(target: File, contents: ArchiveContents) {
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        zip.setLevel(Deflater.BEST_COMPRESSION)
        zip.putNextEntry(ZipEntry(RuleArchiveCodec.MANIFEST_ENTRY))
        zip.write(RuleArchiveCodec.encode(contents.archive).toByteArray())
        zip.closeEntry()
        // Sorted so exporting the same rules twice produces byte-identical files, which lets a user keep
        // archives under version control and see only the changes they actually made.
        contents.bodies.toSortedMap().forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/**
 * Reads [source] back, or null when it is not one of ours — not a zip, or a zip without a manifest.
 *
 * Bodies are looked up through the zip's index by the names the manifest gives, and an entry the
 * manifest never mentions is ignored. Nothing here resolves an entry name against the filesystem, so a
 * crafted name cannot escape anywhere; the stores name the files they write from the rule id instead.
 */
fun readArchiveContainer(source: File): ArchiveContents? = runCatching {
    ZipFile(source).use { zip ->
        val manifestEntry = zip.getEntry(RuleArchiveCodec.MANIFEST_ENTRY) ?: return null
        val archive = zip.getInputStream(manifestEntry).use { RuleArchiveCodec.decode(it.readBytes().decodeToString()) }
            ?: return null
        val bodies = archive.bodyEntryNames().mapNotNull { name ->
            val entry = zip.getEntry(name) ?: return@mapNotNull null
            name to zip.getInputStream(entry).use { it.readBytes() }
        }.toMap()
        ArchiveContents(archive, bodies)
    }
}.getOrNull()

private fun RuleArchive.bodyEntryNames(): List<String> {
    val mapLocal = mapLocal?.nodes.orEmpty().flatMap { it.rules }.map { it.bodyEntry }
    val seeds = seeds?.nodes.orEmpty().flatMap { it.rules }.map { it.bodyEntry }
    return (mapLocal + seeds).filter { it.isNotBlank() }.distinct()
}
