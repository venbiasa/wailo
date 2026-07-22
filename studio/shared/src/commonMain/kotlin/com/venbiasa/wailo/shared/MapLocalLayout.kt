package com.venbiasa.wailo.shared

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * A Map Local group: a named, single-level container the author can toggle as a unit. Groups never
 * nest (ADR-0026). [enabled] gates its rules for matching — a rule in an off group never matches, and
 * its own switch reads disabled in the list while keeping its remembered state. [name] defaults to
 * "New group" and is not part of matching.
 */
data class MapLocalGroup(
    val id: String,
    val name: String = "New group",
    val enabled: Boolean = true,
) {
    companion object {
        /** A stable, unique id for a freshly created group (no java.* so commonMain stays portable). */
        fun newId(): String = "group-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}

/**
 * One top-level entry in the Map Local layout: either a loose (ungrouped) rule or a group with its
 * ordered rules. The layout is a single ordered `List<MapLocalNode>` where groups and loose rules
 * interleave; that top-to-bottom order, flattened, is the match priority — the first active rule that
 * matches wins (the device matches first-in-list, ADR-0026). It is the host's owned, persisted state;
 * `shared` renders it and calls back with a new layout to mutate (ADR-0013).
 */
sealed interface MapLocalNode {
    val id: String
}

data class RuleNode(val rule: MapLocalRuleDef) : MapLocalNode {
    override val id: String get() = rule.id
}

data class GroupNode(val group: MapLocalGroup, val rules: List<MapLocalRuleDef>) : MapLocalNode {
    override val id: String get() = group.id
}

/** Where a dragged rule/group is dropped, resolved by the list UI against the layout minus the dragged item. */
sealed interface MapLocalDropTarget

/** Insert as a top-level node at [index] (0..topLevelCount). Used for loose rules and whole groups. */
data class TopLevelAt(val index: Int) : MapLocalDropTarget

/** Insert a rule as a child of [groupId] at child [index] (0..group.rules.size). */
data class InGroupAt(val groupId: String, val index: Int) : MapLocalDropTarget

// --- Read helpers -----------------------------------------------------------------------------------

/** Every rule in layout (priority) order, regardless of enabled state. */
fun List<MapLocalNode>.allRules(): List<MapLocalRuleDef> = buildList {
    this@allRules.forEach { node ->
        when (node) {
            is RuleNode -> add(node.rule)
            is GroupNode -> addAll(node.rules)
        }
    }
}

/** The rule with [ruleId] anywhere in the layout, or null. */
fun List<MapLocalNode>.findRule(ruleId: String): MapLocalRuleDef? = allRules().firstOrNull { it.id == ruleId }

/** The group holding [ruleId], or null if the rule is loose / not found. */
fun List<MapLocalNode>.groupOf(ruleId: String): MapLocalGroup? =
    filterIsInstance<GroupNode>().firstOrNull { g -> g.rules.any { it.id == ruleId } }?.group

/**
 * Whether [ruleId] is active for matching: its group must be on (or it is loose) AND the rule itself on.
 * This is the effective-enabled the device sees — a rule in an off group is inactive even if its own
 * switch is on.
 */
fun List<MapLocalNode>.isRuleActive(ruleId: String): Boolean {
    forEach { node ->
        when (node) {
            is RuleNode -> if (node.rule.id == ruleId) return node.rule.enabled
            is GroupNode -> node.rules.firstOrNull { it.id == ruleId }?.let { return node.group.enabled && it.enabled }
        }
    }
    return false
}

/** Active rules in priority order (group-on AND rule-on), for compiling the device match-set (ADR-0026). */
fun List<MapLocalNode>.rulesForMatch(): List<MapLocalRuleDef> = buildList {
    this@rulesForMatch.forEach { node ->
        when (node) {
            is RuleNode -> if (node.rule.enabled) add(node.rule)
            is GroupNode -> if (node.group.enabled) node.rules.forEach { if (it.enabled) add(it) }
        }
    }
}

// --- Mutations (pure; each returns a new layout) ----------------------------------------------------

/** Replace [rule] in place by id (loose or grouped); if it isn't present yet, append it as a loose rule. */
fun List<MapLocalNode>.upsertRule(rule: MapLocalRuleDef): List<MapLocalNode> {
    if (allRules().none { it.id == rule.id }) return this + RuleNode(rule)
    return map { node ->
        when (node) {
            is RuleNode -> if (node.rule.id == rule.id) RuleNode(rule) else node
            is GroupNode -> if (node.rules.any { it.id == rule.id }) {
                node.copy(rules = node.rules.map { if (it.id == rule.id) rule else it })
            } else {
                node
            }
        }
    }
}

/** Remove a rule by id from wherever it lives; a now-empty group is kept (empty groups are allowed). */
fun List<MapLocalNode>.removeRule(ruleId: String): List<MapLocalNode> = map { node ->
    when (node) {
        is RuleNode -> node
        is GroupNode -> node.copy(rules = node.rules.filterNot { it.id == ruleId })
    }
}.filterNot { it is RuleNode && it.rule.id == ruleId }

/** Remove a group AND its rules (delete-all, ADR-0026). */
fun List<MapLocalNode>.removeGroup(groupId: String): List<MapLocalNode> =
    filterNot { it is GroupNode && it.group.id == groupId }

/** Append a new (empty) group at the end of the top level. */
fun List<MapLocalNode>.addGroup(group: MapLocalGroup): List<MapLocalNode> = this + GroupNode(group, emptyList())

fun List<MapLocalNode>.setGroupEnabled(groupId: String, enabled: Boolean): List<MapLocalNode> =
    map { if (it is GroupNode && it.group.id == groupId) it.copy(group = it.group.copy(enabled = enabled)) else it }

fun List<MapLocalNode>.renameGroup(groupId: String, name: String): List<MapLocalNode> =
    map { if (it is GroupNode && it.group.id == groupId) it.copy(group = it.group.copy(name = name)) else it }

fun List<MapLocalNode>.setRuleEnabled(ruleId: String, enabled: Boolean): List<MapLocalNode> = map { node ->
    when (node) {
        is RuleNode -> if (node.rule.id == ruleId) RuleNode(node.rule.copy(enabled = enabled)) else node
        is GroupNode -> if (node.rules.any { it.id == ruleId }) {
            node.copy(rules = node.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
        } else {
            node
        }
    }
}

/**
 * Move a rule to [target]. The rule is first removed from its current spot, then inserted — so the
 * caller resolves [target] against the layout *with the dragged rule already removed* (that is how the
 * list UI computes drop indices), keeping indices consistent.
 */
fun List<MapLocalNode>.moveRule(ruleId: String, target: MapLocalDropTarget): List<MapLocalNode> {
    val rule = findRule(ruleId) ?: return this
    return removeRule(ruleId).insertRule(rule, target)
}

private fun List<MapLocalNode>.insertRule(rule: MapLocalRuleDef, target: MapLocalDropTarget): List<MapLocalNode> =
    when (target) {
        is TopLevelAt -> toMutableList().apply { add(target.index.coerceIn(0, size), RuleNode(rule)) }
        is InGroupAt -> map { node ->
            if (node is GroupNode && node.group.id == target.groupId) {
                node.copy(rules = node.rules.toMutableList().apply { add(target.index.coerceIn(0, size), rule) })
            } else {
                node
            }
        }
    }

/** Move a whole group to top-level position [index], resolved against the layout minus the group. */
fun List<MapLocalNode>.moveGroup(groupId: String, index: Int): List<MapLocalNode> {
    val node = firstOrNull { it is GroupNode && it.group.id == groupId } ?: return this
    return filterNot { it.id == groupId }.toMutableList().apply { add(index.coerceIn(0, size), node) }
}

// --- Persistence codec ------------------------------------------------------------------------------

/**
 * Serializes a Map Local layout to/from a single string for the host's primitive key-value store. One
 * line per entry, `|`-separated fields, each free-text field Base64-encoded so it can never collide with
 * the delimiters. Line kinds: `G` = group header, `C` = a child rule of the current group, `R` = a loose
 * (top-level) rule. A group's children are contiguous right after its header, so the type prefix alone
 * reconstructs the interleaved layout unambiguously.
 *
 * Migration: lines with no type prefix are the pre-groups format (the rule's id led the line) and decode
 * as loose rules — so old prefs load unchanged (ADR-0026, extending ADR-0019/0021's field-growth pattern).
 * Kept pure (no filesystem, no java.*) so it lives in `shared` and is unit-tested.
 */
@OptIn(ExperimentalEncodingApi::class)
object MapLocalLayoutCodec {
    private const val LINE_SEP = "\n"
    private const val FIELD_SEP = "|"
    private const val METHOD_SEP = ","
    private const val HEADER_SEP = ","
    private const val HEADER_KV = ":"
    private const val TYPE_GROUP = "G"
    private const val TYPE_CHILD = "C"
    private const val TYPE_LOOSE = "R"

    fun encode(nodes: List<MapLocalNode>): String = buildList {
        nodes.forEach { node ->
            when (node) {
                is GroupNode -> {
                    add(listOf(TYPE_GROUP, node.group.id, if (node.group.enabled) "1" else "0", enc(node.group.name)).joinToString(FIELD_SEP))
                    node.rules.forEach { add(TYPE_CHILD + FIELD_SEP + encodeRule(it)) }
                }
                is RuleNode -> add(TYPE_LOOSE + FIELD_SEP + encodeRule(node.rule))
            }
        }
    }.joinToString(LINE_SEP)

    fun decode(text: String): List<MapLocalNode> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<MapLocalNode>()
        var pendingGroup: MapLocalGroup? = null
        val pendingRules = mutableListOf<MapLocalRuleDef>()
        fun flushGroup() {
            pendingGroup?.let { result.add(GroupNode(it, pendingRules.toList())) }
            pendingGroup = null
            pendingRules.clear()
        }
        text.split(LINE_SEP).forEach { line ->
            val fields = line.split(FIELD_SEP)
            when (fields.firstOrNull()) {
                TYPE_GROUP -> {
                    flushGroup()
                    pendingGroup = parseGroup(fields)
                }
                TYPE_CHILD -> decodeRule(fields.drop(1))?.let {
                    if (pendingGroup != null) pendingRules.add(it) else result.add(RuleNode(it))
                }
                TYPE_LOOSE -> {
                    flushGroup()
                    decodeRule(fields.drop(1))?.let { result.add(RuleNode(it)) }
                }
                // No recognized prefix: a legacy (pre-groups) rule line whose fields are the rule itself.
                else -> {
                    flushGroup()
                    decodeRule(fields)?.let { result.add(RuleNode(it)) }
                }
            }
        }
        flushGroup()
        return result
    }

    private fun parseGroup(fields: List<String>): MapLocalGroup? {
        if (fields.size < 4) return null
        return MapLocalGroup(
            id = fields[1],
            name = dec(fields[3]).ifBlank { "New group" },
            enabled = fields[2] == "1",
        )
    }

    private fun encodeRule(rule: MapLocalRuleDef): String = listOf(
        rule.id,
        if (rule.enabled) "1" else "0",
        enc(rule.urlPattern),
        enc(rule.method),
        enc(rule.filePath),
        rule.statusCode.toString(),
        encodeHeaders(rule.headers),
        if (rule.inline) "1" else "0",
        enc(rule.name),
    ).joinToString(FIELD_SEP)

    private fun decodeRule(parts: List<String>): MapLocalRuleDef? {
        // 7 fields = pre-inline (file-backed) legacy, 8 = pre-name, 9 = current.
        if (parts.size !in 7..9) return null
        return MapLocalRuleDef(
            id = parts[0],
            // Rules saved before names existed migrate to "Untitled" (a blank name can't be saved now).
            name = if (parts.size >= 9) dec(parts[8]).ifBlank { "Untitled" } else "Untitled",
            enabled = parts[1] == "1",
            urlPattern = dec(parts[2]),
            // A rule now matches a single method; a legacy multi-method line collapses to its first.
            method = dec(parts[3]).substringBefore(METHOD_SEP).trim(),
            filePath = dec(parts[4]),
            statusCode = parts[5].toIntOrNull() ?: 200,
            headers = decodeHeaders(parts[6]),
            inline = parts.size >= 8 && parts[7] == "1",
        )
    }

    private fun encodeHeaders(headers: List<MapLocalHeader>): String =
        headers.filter { it.name.isNotBlank() }
            .joinToString(HEADER_SEP) { enc(it.name) + HEADER_KV + enc(it.value) }

    // A legacy line's field[6] once held a lone Base64 Content-Type; a Base64 token has no ":" separator,
    // so its absence marks that legacy shape — migrate it to a Content-Type header. Blank = no headers.
    private fun decodeHeaders(field: String): List<MapLocalHeader> {
        if (field.isBlank()) return emptyList()
        if (!field.contains(HEADER_KV)) return listOf(MapLocalHeader("Content-Type", dec(field)))
        return field.split(HEADER_SEP).mapNotNull { part ->
            val kv = part.split(HEADER_KV)
            if (kv.size != 2) null else MapLocalHeader(dec(kv[0]), dec(kv[1]))
        }
    }

    // Base64 (RFC 4648, padded) — byte-compatible with the prior java.util.Base64 encoding, so values
    // written before this codec still decode.
    private fun enc(value: String): String = Base64.encode(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
}
