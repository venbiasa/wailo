package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.shared.ArchivedMapLocalRule
import com.venbiasa.wailo.shared.ArchivedSection
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.CaptureFilterState
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.RuleArchive
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.mergeIn
import com.venbiasa.wailo.shared.toArchived
import com.venbiasa.wailo.shared.toArchivedNodes
import com.venbiasa.wailo.shared.toLayoutNodes
import com.venbiasa.wailo.shared.toRuleDef
import java.io.File
import java.time.Instant

/**
 * The host half of rule export/import: the body stores and the user-facing outcome. `shared` owns the
 * archive schema, the layout conversions, and the merge; `RuleArchiveContainer` owns the zip.
 */

/** A merge's result, ready to be adopted as the panel's state, plus the line shown to the user. */
data class ArchiveImport(
    val mapLocal: List<MapLocalNode>,
    val breakpoints: List<BreakpointNode>,
    val seeds: List<SeedNode>,
    val captureFilter: CaptureFilterState,
    val message: String,
)

/**
 * Collects everything authored into one archive, reading each rule's body out of whichever store or
 * file currently serves it. Bodies come back as container entries rather than fields on the manifest,
 * so this returns both halves together.
 */
fun buildArchive(
    mapLocalNodes: List<MapLocalNode>,
    mapLocalEnabled: Boolean,
    breakpointNodes: List<BreakpointNode>,
    breakpointsEnabled: Boolean,
    seedNodes: List<SeedNode>,
    seedsEnabled: Boolean,
    captureFilter: CaptureFilterState,
): ArchiveContents {
    val bodies = mutableMapOf<String, ByteArray>()

    // loadServedBody resolves through whichever source the rule uses, so a file-backed rule exports its
    // actual bytes rather than an empty managed file that was never written.
    val mapLocal = mapLocalNodes.toArchivedNodes { rule ->
        rule.toArchived(bodies.addBody("map-local", rule.id, rule.bodyExtension(), MapLocalStore.loadServedBody(rule)))
    }
    val seeds = seedNodes.toArchivedNodes { seed ->
        seed.toArchived(bodies.addBody("seeds", seed.id, seed.bodyExtension(), SeedStore.loadBody(seed)))
    }

    return ArchiveContents(
        archive = RuleArchive(
            exportedAt = Instant.now().toString(),
            mapLocal = ArchivedSection(enabled = mapLocalEnabled, nodes = mapLocal),
            breakpoints = ArchivedSection(
                enabled = breakpointsEnabled,
                nodes = breakpointNodes.toArchivedNodes { it.toArchived() },
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
    seedNodes: List<SeedNode>,
    captureFilter: CaptureFilterState,
): ArchiveImport {
    val contents = readArchiveContainer(source)
        ?: return unchanged(mapLocalNodes, breakpointNodes, seedNodes, captureFilter, "${source.name} isn't a Wailo rules file.")
    val archive = contents.archive

    val added = mutableListOf<String>()

    var mapLocal = mapLocalNodes
    archive.mapLocal?.let { section ->
        // Resolve each archived path once, here: `toRuleDef` decides file-backed vs inline from whether
        // the file is actually on *this* machine, which is a filesystem question `shared` cannot ask.
        val incoming = section.nodes.toLayoutNodes { it.toRuleDef(filePathResolves = it.filePathResolves()) }
        val merge = mapLocal.mergeIn(incoming)
        mapLocal = merge.nodes
        val archivedById = section.nodes.flatMap { it.rules }.associateBy { it.id }
        merge.addedRuleIds.forEach { id ->
            val rule = merge.nodes.allRules().firstOrNull { it.id == id } ?: return@forEach
            // A rule that kept its own file reads from there; a managed copy beside it would be dead weight.
            if (!rule.inline) return@forEach
            val bytes = contents.bodies[archivedById[id]?.bodyEntry] ?: return@forEach
            MapLocalStore.saveInlineBody(rule, bytes)
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
            val seed = merge.nodes.allRules().firstOrNull { it.id == id } ?: return@forEach
            val bytes = contents.bodies[archivedById[id]?.bodyEntry] ?: return@forEach
            SeedStore.saveBody(seed, bytes)
        }
        if (merge.addedRuleIds.isNotEmpty()) added += "${merge.addedRuleIds.size} seed"
    }

    val filter = archive.captureFilter?.let { captureFilter.mergeIn(it) } ?: captureFilter
    val filterGrew = filter.allowHosts.size + filter.blockHosts.size >
        captureFilter.allowHosts.size + captureFilter.blockHosts.size
    if (filterGrew) added += "capture filter hosts"

    val message = when {
        added.isEmpty() -> "Nothing new in ${source.name} — every rule in it is already here."
        else -> "Imported ${added.joinToString(", ")} from ${source.name}."
    }
    return ArchiveImport(mapLocal, breakpoints, seeds, filter, message)
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

// The rule's own Content-Type decides the entry's extension, so a body opens in the right editor when
// someone browses the archive instead of importing it.
private fun MapLocalRuleDef.bodyExtension(): String = extensionForContentType(
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
        ?: filePath.takeIf { it.isNotBlank() }?.let(::guessContentType),
)

private fun SeedRuleDef.bodyExtension(): String = extensionForContentType(
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value,
)

private fun ArchivedMapLocalRule.filePathResolves(): Boolean =
    filePath.isNotBlank() && runCatching { File(filePath).isFile }.getOrDefault(false)

private fun unchanged(
    mapLocal: List<MapLocalNode>,
    breakpoints: List<BreakpointNode>,
    seeds: List<SeedNode>,
    captureFilter: CaptureFilterState,
    message: String,
) = ArchiveImport(mapLocal, breakpoints, seeds, captureFilter, message)
