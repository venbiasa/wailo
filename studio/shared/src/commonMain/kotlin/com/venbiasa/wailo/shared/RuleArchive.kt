package com.venbiasa.wailo.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The manifest of an exported set of authored rules — Map Local, breakpoints, seeds, and the capture
 * filter — as it is stored inside an archive container (see [RuleArchiveCodec]).
 *
 * Deliberately a *second*, explicit schema rather than the layout codecs the prefs use: those are
 * positional `|`-separated Base64 lines tuned for a key-value store, so a field added in the middle
 * shifts every later one. A file a user keeps for months (or hands to a colleague) has to survive that,
 * so every value is a named key with a default, and [RuleArchiveCodec] ignores keys it doesn't know —
 * an archive from a newer Studio loses only the fields this build has never heard of.
 *
 * Bodies travel *beside* this manifest as their own container entries, named here by [ArchivedMapLocalRule.bodyEntry]
 * and [ArchivedSeedRule.bodyEntry]. They have to travel somehow: an archive whose rules only pointed at
 * the author's own paths would import as a set of broken mocks on any other machine, which is the
 * failure this feature exists to prevent. [ArchivedMapLocalRule.filePath] is still carried so a rule can
 * go back to reading the author's live file when that file is actually there — see its own doc.
 *
 * Every section is optional so the same schema covers a whole-profile backup and a single-tool export.
 * A `null` section means "this file says nothing about that tool", which an import must treat as
 * leave-alone rather than as empty.
 */
@Serializable
data class RuleArchive(
    val formatVersion: Int = RULE_ARCHIVE_VERSION,
    /** ISO-8601, supplied by the host — `commonMain` has no clock. Informational only. */
    val exportedAt: String = "",
    val mapLocal: ArchivedSection<ArchivedMapLocalRule>? = null,
    val breakpoints: ArchivedSection<ArchivedBreakpointRule>? = null,
    val seeds: ArchivedSection<ArchivedSeedRule>? = null,
    val captureFilter: ArchivedCaptureFilter? = null,
)

const val RULE_ARCHIVE_VERSION = 1

/** One tool's contents: its feature master plus its grouped layout in priority order. */
@Serializable
data class ArchivedSection<R>(
    val enabled: Boolean = true,
    val nodes: List<ArchivedNode<R>> = emptyList(),
)

/**
 * One top-level entry, mirroring [LayoutNode]: a `null` [group] is a loose rule (and [rules] holds
 * exactly that one), otherwise it is a group and [rules] are its children. Modelled as one type with a
 * nullable group rather than a sealed hierarchy so the JSON needs no type discriminator to read.
 */
@Serializable
data class ArchivedNode<R>(
    val group: ArchivedGroup? = null,
    val rules: List<R> = emptyList(),
)

@Serializable
data class ArchivedGroup(
    val id: String,
    val name: String = "New group",
    val enabled: Boolean = true,
)

@Serializable
data class ArchivedHeader(val name: String, val value: String)

/**
 * [filePath] and [bodyEntry] are both written for a file-backed rule, and the importer prefers the path
 * when it still resolves on the importing machine. That keeps the author's own "edit the file, the mock
 * follows" loop intact across a re-import, while a colleague — for whom that path is meaningless — still
 * gets a working rule from the copy inside the archive.
 *
 * [bodyEntry] names an entry in the container, blank when the rule has no body. It is written by the
 * host and only ever read back through the container's own index, never used as a path to write to.
 */
@Serializable
data class ArchivedMapLocalRule(
    val id: String,
    val name: String = "Untitled",
    val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val statusCode: Int = 200,
    val delayMillis: Int = 0,
    val headers: List<ArchivedHeader> = emptyList(),
    val inline: Boolean = true,
    val filePath: String = "",
    val bodyEntry: String = "",
)

@Serializable
data class ArchivedBreakpointRule(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val onRequest: Boolean = false,
    val onResponse: Boolean = true,
)

/** [bodyEntry] names an entry in the container, as on [ArchivedMapLocalRule]. */
@Serializable
data class ArchivedSeedRule(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val statusCode: Int = 200,
    val delayMillis: Int = 0,
    val headers: List<ArchivedHeader> = emptyList(),
    val bodyEntry: String = "",
)

@Serializable
data class ArchivedCaptureFilter(
    val masterEnabled: Boolean = true,
    val allowEnabled: Boolean = false,
    val allowHosts: List<String> = emptyList(),
    val blockEnabled: Boolean = false,
    val blockHosts: List<String> = emptyList(),
)

/**
 * The archive format: a compressed container holding [MANIFEST_ENTRY] plus one entry per body under
 * [BODIES_PREFIX]. The container is a zip, so a colleague who is sent one can open it with the tool
 * their OS already has and read what it will serve *before* trusting it — the reason for a container
 * rather than a single compressed blob, which arrives as something you can only import or discard.
 *
 * Bodies sit outside the manifest because encoding them into it costs a third of their size in Base64
 * before compression, and compressing an already-encoded PNG wins almost none of that back. As entries
 * they stay exactly the bytes they were, named with the extension their Content-Type implies.
 *
 * This object owns the *names*; reading and writing the container is the host's, which is where file IO
 * belongs (ADR-0013) — `shared` stays free of `java.*`.
 */
object RuleArchiveCodec {
    /**
     * The extension the host's save dialog defaults to, so an archive is recognizable on disk.
     *
     * Deliberately says nothing about *which* tools are inside. Every section is optional, so one file
     * covers a single-tool export and a whole-profile backup alike, and a tool added later is a new
     * manifest field rather than a second file type — which a name like "rules" would have outgrown the
     * first time something that isn't a rule went in.
     */
    const val FILE_EXTENSION = "wak"

    /** The manifest's entry name. Its presence is what makes a zip *this* format rather than any other. */
    const val MANIFEST_ENTRY = "manifest.json"

    /** Body entries live under here, one folder per tool, so the container reads as a browsable tree. */
    const val BODIES_PREFIX = "bodies/"

    private val json = Json {
        // Kept readable even though it is compressed: the container exists to be opened, and indentation
        // costs almost nothing once deflated.
        prettyPrint = true
        // Every field written out, so the file reads as the complete state rather than as a diff
        // against defaults a reader would have to know.
        encodeDefaults = true
        // A file written by a newer Studio still imports, minus the fields this build cannot model.
        ignoreUnknownKeys = true
    }

    fun encode(archive: RuleArchive): String = json.encodeToString(archive)

    /** Null when [text] isn't a readable manifest — the host reports that rather than importing nothing. */
    fun decode(text: String): RuleArchive? =
        runCatching { json.decodeFromString<RuleArchive>(text) }.getOrNull()
}

// --- Layout <-> archive ------------------------------------------------------------------------------

fun <T : LayoutRule<T>, R> List<LayoutNode<T>>.toArchivedNodes(rule: (T) -> R): List<ArchivedNode<R>> =
    map { node ->
        when (node) {
            is RuleNode -> ArchivedNode(group = null, rules = listOf(rule(node.rule)))
            is GroupNode -> ArchivedNode(
                group = ArchivedGroup(node.group.id, node.group.name, node.group.enabled),
                rules = node.rules.map(rule),
            )
        }
    }

fun <T : LayoutRule<T>, R> List<ArchivedNode<R>>.toLayoutNodes(rule: (R) -> T): List<LayoutNode<T>> =
    mapNotNull { node ->
        val group = node.group
        if (group == null) {
            // A loose entry with no rule is malformed; drop it rather than fail the whole import.
            node.rules.firstOrNull()?.let { RuleNode(rule(it)) }
        } else {
            GroupNode(RuleGroup(group.id, group.name, group.enabled), node.rules.map(rule))
        }
    }

fun MapLocalRuleDef.toArchived(bodyEntry: String): ArchivedMapLocalRule = ArchivedMapLocalRule(
    id = id,
    name = name,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    delayMillis = delayMillis,
    headers = headers.map { ArchivedHeader(it.name, it.value) },
    inline = inline,
    filePath = filePath,
    bodyEntry = bodyEntry,
)

/**
 * [filePathResolves] answers whether this machine still has the archived file; only then does the rule
 * come back file-backed. Everywhere else it becomes an inline rule serving the archive's own copy, so
 * an imported mock answers on the first request instead of falling through to the network.
 */
fun ArchivedMapLocalRule.toRuleDef(filePathResolves: Boolean): MapLocalRuleDef {
    val keepsFile = !inline && filePath.isNotBlank() && filePathResolves
    return MapLocalRuleDef(
        id = id,
        name = name.ifBlank { "Untitled" },
        enabled = enabled,
        urlPattern = urlPattern,
        method = method,
        filePath = if (keepsFile) filePath else "",
        statusCode = statusCode,
        delayMillis = delayMillis.coerceAtLeast(0),
        headers = headers.map { ResponseHeader(it.name, it.value) },
        inline = !keepsFile,
    )
}

fun BreakpointRuleDef.toArchived(): ArchivedBreakpointRule = ArchivedBreakpointRule(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    onRequest = onRequest,
    onResponse = onResponse,
)

fun ArchivedBreakpointRule.toRuleDef(): BreakpointRuleDef = BreakpointRuleDef(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    onRequest = onRequest,
    onResponse = onResponse,
)

fun SeedRuleDef.toArchived(bodyEntry: String): ArchivedSeedRule = ArchivedSeedRule(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    delayMillis = delayMillis,
    headers = headers.map { ArchivedHeader(it.name, it.value) },
    bodyEntry = bodyEntry,
)

fun ArchivedSeedRule.toRuleDef(): SeedRuleDef = SeedRuleDef(
    id = id,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    delayMillis = delayMillis.coerceAtLeast(0),
    headers = headers.map { ResponseHeader(it.name, it.value) },
)

fun CaptureFilterState.toArchived(): ArchivedCaptureFilter = ArchivedCaptureFilter(
    masterEnabled = masterEnabled,
    allowEnabled = allowEnabled,
    allowHosts = allowHosts,
    blockEnabled = blockEnabled,
    blockHosts = blockHosts,
)

// --- Merge -------------------------------------------------------------------------------------------

/**
 * What a merge added, so the host can restore bodies for exactly the rules that landed and say what
 * happened. Ids already present are absent from both lists — nothing was overwritten.
 */
data class LayoutMerge<T : LayoutRule<T>>(
    val nodes: List<LayoutNode<T>>,
    val addedRuleIds: List<String>,
    val addedGroupIds: List<String>,
)

/**
 * Folds [incoming] into this layout, keeping every rule already here untouched: an id that exists is
 * skipped, never replaced. Rules land where the archive had them — a group that already exists takes
 * its new children, an unknown group arrives whole — and anything new is appended at the end, which is
 * the lowest match priority (ADR-0026), so an import can never quietly shadow a rule already in use.
 *
 * Re-importing the same file is therefore a no-op, and restoring a rule you have since edited means
 * deleting your copy first — the cost of never silently reverting an edit.
 */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.mergeIn(incoming: List<LayoutNode<T>>): LayoutMerge<T> {
    val knownRuleIds = allRules().mapTo(HashSet()) { it.id }
    val knownGroupIds = HashSet<String>()
    forEach { if (it is GroupNode) knownGroupIds.add(it.group.id) }

    val addedRuleIds = mutableListOf<String>()
    val addedGroupIds = mutableListOf<String>()
    var result = this

    incoming.forEach { node ->
        when (node) {
            is RuleNode -> if (knownRuleIds.add(node.rule.id)) {
                result = result + RuleNode(node.rule)
                addedRuleIds += node.rule.id
            }
            is GroupNode -> {
                // `add` returns false for an id already known, so this both skips existing rules and
                // dedupes an archive that repeats one.
                val fresh = node.rules.filter { knownRuleIds.add(it.id) }
                when {
                    node.group.id in knownGroupIds -> if (fresh.isNotEmpty()) {
                        result = result.map { existing ->
                            if (existing is GroupNode && existing.group.id == node.group.id) {
                                existing.copy(rules = existing.rules + fresh)
                            } else {
                                existing
                            }
                        }
                        addedRuleIds += fresh.map { it.id }
                    }
                    // A group whose every rule was already here contributes nothing; adding it empty
                    // would litter the panel. One that was empty in the archive is itself the content.
                    fresh.isNotEmpty() || node.rules.isEmpty() -> {
                        knownGroupIds += node.group.id
                        result = result + GroupNode(node.group, fresh)
                        addedGroupIds += node.group.id
                        addedRuleIds += fresh.map { it.id }
                    }
                }
            }
        }
    }
    return LayoutMerge(result, addedRuleIds, addedGroupIds)
}

/**
 * Unions the archived hosts into this filter. The three switches are left as they are: an import adds
 * what a colleague filters, it does not decide whether your capture is filtered at all.
 */
fun CaptureFilterState.mergeIn(incoming: ArchivedCaptureFilter): CaptureFilterState {
    val withAllow = incoming.allowHosts.fold(this) { acc, host -> acc.addAllow(host) }
    return incoming.blockHosts.fold(withAllow) { acc, host -> acc.addBlock(host) }
}
