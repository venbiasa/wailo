package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.ArchivedSection
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.RuleArchive
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.ScriptNode
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.mergeIn
import com.venbiasa.wailo.shared.toArchived
import com.venbiasa.wailo.shared.toArchivedNodes
import com.venbiasa.wailo.shared.toLayoutNodes
import com.venbiasa.wailo.shared.toRuleDef
import java.io.File
import java.time.Instant

/**
 * The host half of rule export/import: the bodies and the user-facing outcome. `shared` owns the
 * archive schema, the layout conversions, and the merge; `RuleArchiveContainer` owns the zip.
 *
 * Bodies are passed in and handed back rather than read and written here, because the daemon holds the
 * only copy of them (ADR-0085) — an archive is a conversion between its zip entries and the bytes that
 * ride along with the layout Studio publishes.
 */

/** A merge's result, ready to be adopted as the panel's state, plus the line shown to the user. */
data class ArchiveImport(
    val mapLocal: List<MapLocalNode>,
    val breakpoints: List<BreakpointNode>,
    val scripts: List<ScriptNode>,
    val seeds: List<SeedNode>,
    val captureFilter: CaptureFilterState,
    /** Bodies for the rules that landed, by rule id, to publish alongside the layouts above. */
    val bodies: Map<String, ByteArray>,
    val message: String,
)

/**
 * Collects everything authored into one archive. Bodies come back as container entries rather than
 * fields on the manifest, so this returns both halves together.
 */
suspend fun buildArchive(
    mapLocalNodes: List<MapLocalNode>,
    mapLocalEnabled: Boolean,
    breakpointNodes: List<BreakpointNode>,
    breakpointsEnabled: Boolean,
    scriptNodes: List<ScriptNode>,
    scriptsEnabled: Boolean,
    seedNodes: List<SeedNode>,
    seedsEnabled: Boolean,
    captureFilter: CaptureFilterState,
    // Suspending because a body is fetched from the daemon one rule at a time (ADR-0086). An export is
    // the one caller that wants all of them, and it pulls each once on its way into the zip.
    mapLocalBody: suspend (String) -> ByteArray,
    seedBody: suspend (String) -> ByteArray,
): ArchiveContents {
    val bodies = mutableMapOf<String, ByteArray>()

    // Fetched up front rather than inside the conversions below, which are `shared`'s and not suspending.
    // Every one is bound for the zip anyway, so nothing is held that was not about to be.
    val mapLocalBytes = mapLocalNodes.allRules().associate { it.id to mapLocalBody(it.id) }
    val seedBytes = seedNodes.allRules().associate { it.id to seedBody(it.id) }

    val mapLocal = mapLocalNodes.toArchivedNodes { rule ->
        val entry = bodies.addBody("map-local", rule.id, rule.bodyExtension(), mapLocalBytes.bytesFor(rule.id))
        rule.toArchived(entry)
    }
    val seeds = seedNodes.toArchivedNodes { seed ->
        val entry = bodies.addBody("seeds", seed.id, seed.bodyExtension(), seedBytes.bytesFor(seed.id))
        seed.toArchived(entry)
    }

    return ArchiveContents(
        archive = RuleArchive(
            exportedAt = Instant.now().toString(),
            mapLocal = ArchivedSection(enabled = mapLocalEnabled, nodes = mapLocal),
            breakpoints = ArchivedSection(
                enabled = breakpointsEnabled,
                nodes = breakpointNodes.toArchivedNodes { it.toArchived() },
            ),
            scripts = ArchivedSection(
                enabled = scriptsEnabled,
                nodes = scriptNodes.toArchivedNodes { it.toArchived() },
            ),
            seeds = ArchivedSection(enabled = seedsEnabled, nodes = seeds),
            captureFilter = captureFilter.toArchived(),
        ),
        bodies = bodies,
    )
}

/** Writes [contents] to [target]. Returns the line to show — the path on success, the reason on failure. */
fun writeArchive(target: File, contents: ArchiveContents): String {
    val failure = runCatching { writeArchiveContainer(target, contents) }.exceptionOrNull()
    return if (failure == null) "Exported to ${target.path}." else "Could not export: ${failure.message}"
}

/**
 * Reads [source] and folds it into the current layouts, adding only ids that aren't already present
 * (see `mergeIn`). Bodies are restored for exactly the rules that landed, so a skipped id keeps the
 * body it already had. A section the file omits leaves that tool untouched.
 */
fun importArchive(
    source: File,
    mapLocalNodes: List<MapLocalNode>,
    breakpointNodes: List<BreakpointNode>,
    scriptNodes: List<ScriptNode>,
    seedNodes: List<SeedNode>,
    captureFilter: CaptureFilterState,
): ArchiveImport {
    val contents = readArchiveContainer(source)
        ?: return unchanged(
            mapLocalNodes,
            breakpointNodes,
            scriptNodes,
            seedNodes,
            captureFilter,
            "${source.name} isn't a Wailo rules file.",
        )
    val archive = contents.archive

    val added = mutableListOf<String>()
    val bodies = mutableMapOf<String, ByteArray>()

    var mapLocal = mapLocalNodes
    archive.mapLocal?.let { section ->
        // Always the archive's own copy: a rule's bytes live on the daemon now, so an archived path
        // that still resolves on this machine is a stale pointer rather than a live source (ADR-0085).
        val incoming = section.nodes.toLayoutNodes { it.toRuleDef(filePathResolves = false) }
        val merge = mapLocal.mergeIn(incoming)
        mapLocal = merge.nodes
        val archivedById = section.nodes.flatMap { it.rules }.associateBy { it.id }
        merge.addedRuleIds.forEach { id ->
            contents.bodies[archivedById[id]?.bodyEntry]?.let { bodies[id] = it }
        }
        if (merge.addedRuleIds.isNotEmpty()) added += "${merge.addedRuleIds.size} Map Local"
    }

    var breakpoints = breakpointNodes
    archive.breakpoints?.let { section ->
        val merge = breakpoints.mergeIn(section.nodes.toLayoutNodes { it.toRuleDef() })
        breakpoints = merge.nodes
        if (merge.addedRuleIds.isNotEmpty()) added += "${merge.addedRuleIds.size} breakpoint"
    }

    var seeds = seedNodes
    archive.seeds?.let { section ->
        val merge = seeds.mergeIn(section.nodes.toLayoutNodes { it.toRuleDef() })
        seeds = merge.nodes
        val archivedById = section.nodes.flatMap { it.rules }.associateBy { it.id }
        merge.addedRuleIds.forEach { id ->
            contents.bodies[archivedById[id]?.bodyEntry]?.let { bodies[id] = it }
        }
        if (merge.addedRuleIds.isNotEmpty()) added += "${merge.addedRuleIds.size} seed"
    }

    var scripts = scriptNodes
    archive.scripts?.let { section ->
        val merge = scripts.mergeIn(section.nodes.toLayoutNodes { it.toRuleDef() })
        scripts = merge.nodes
        if (merge.addedRuleIds.isNotEmpty()) added += "${merge.addedRuleIds.size} script"
    }

    val filter = archive.captureFilter?.let { captureFilter.mergeIn(it) } ?: captureFilter
    val filterGrew = filter.allowHosts.size + filter.blockHosts.size >
        captureFilter.allowHosts.size + captureFilter.blockHosts.size
    if (filterGrew) added += "capture filter hosts"

    val message = when {
        added.isEmpty() -> "Nothing new in ${source.name} — every rule in it is already here."
        else -> "Imported ${added.joinToString(", ")} from ${source.name}."
    }
    return ArchiveImport(mapLocal, breakpoints, scripts, seeds, filter, bodies, message)
}

/**
 * Records [bytes] under a fresh entry name and returns it, or returns blank for a rule with no body so
 * the manifest says "nothing to restore" rather than naming an empty entry.
 */
private fun MutableMap<String, ByteArray>.addBody(tool: String, id: String, extension: String, bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val name = bodyEntryName(tool, id, extension, keys)
    put(name, bytes)
    return name
}

private fun Map<String, ByteArray>.bytesFor(id: String): ByteArray = this[id] ?: ByteArray(0)

// The rule's own Content-Type decides the entry's extension, so a body opens in the right editor when
// someone browses the archive instead of importing it.
private fun MapLocalRuleDef.bodyExtension(): String = extensionForContentType(
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
        ?: filePath.takeIf { it.isNotBlank() }?.let(::guessContentType),
)

private fun SeedRuleDef.bodyExtension(): String = extensionForContentType(
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value,
)

private fun unchanged(
    mapLocal: List<MapLocalNode>,
    breakpoints: List<BreakpointNode>,
    scripts: List<ScriptNode>,
    seeds: List<SeedNode>,
    captureFilter: CaptureFilterState,
    message: String,
) = ArchiveImport(mapLocal, breakpoints, scripts, seeds, captureFilter, emptyMap(), message)
