package com.venbiasa.wailo.daemon

import kotlinx.serialization.Serializable

/**
 * The daemon's own grouped-rule structure, and the pure operations over it.
 *
 * ADR-0061 carried Studio's grouping as an opaque blob, which made the daemon unable to place a rule an
 * agent authored — so an MCP write never reached the panel. The structure lives here instead, in the
 * daemon's own types: `daemon` still must not depend on `shared` (invariant #2), so this mirrors that
 * module's `LayoutNode` shape rather than importing it, the same way [MapLocalRuleDto] mirrors its rule.
 *
 * Everything in this file is pure and free of the host, so the ordering rules can be tested without a
 * running daemon.
 */

/**
 * A single-level group. Groups never nest (ADR-0026), so a group holds rules and never other groups.
 *
 * Named for the daemon rather than plainly, because `shared` has its own `RuleGroup` for the same idea
 * and Studio converts between the two — one of them has to survive being imported beside the other.
 */
@Serializable
data class DaemonRuleGroup(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
)

/**
 * One top-level entry: a group with its ordered rules, or — when [group] is null — a single loose rule.
 *
 * Holding the tree rather than a flat list plus membership tags is what lets an empty group exist. Studio
 * creates one before it is named, so a representation that could only describe a group through its
 * members would delete it between those two steps.
 */
@Serializable
data class DaemonRuleNode<T>(
    val group: DaemonRuleGroup? = null,
    val rules: List<T> = emptyList(),
)

/** Flattened in top-to-bottom order, which is the match priority the engine consumes (ADR-0026). */
fun <T> List<DaemonRuleNode<T>>.flattenRules(): List<T> = flatMap { it.rules }

/** Converts the rules while leaving the structure alone — the wire/domain seam for a whole layout. */
fun <A, B> List<DaemonRuleNode<A>>.mapRules(transform: (A) -> B): List<DaemonRuleNode<B>> =
    map { DaemonRuleNode(it.group, it.rules.map(transform)) }

/** The group each rule sits in, by rule id, for frontends that report a rule's placement. */
fun <T> List<DaemonRuleNode<T>>.groupIdByRule(idOf: (T) -> String): Map<String, String> =
    buildMap {
        for (node in this@groupIdByRule) {
            val groupId = node.group?.id ?: continue
            node.rules.forEach { put(idOf(it), groupId) }
        }
    }

fun <T> List<DaemonRuleNode<T>>.groups(): List<DaemonRuleGroup> = mapNotNull { it.group }

internal fun <T> List<DaemonRuleNode<T>>.findRule(id: String, idOf: (T) -> String): T? =
    firstNotNullOfOrNull { node -> node.rules.firstOrNull { idOf(it) == id } }

/**
 * Replaces the rule with [rule]'s id where it already sits, or adds it when it is new.
 *
 * An existing rule keeps its position unless [groupId] moves it, because an agent correcting a body
 * should not silently change which rule wins a match. A new one lands last — the lowest priority — so an
 * automated write cannot shadow a rule the user is already relying on, matching how an imported archive
 * merges (ADR-0080).
 *
 * Returns null when [groupId] names a group that does not exist: groups are addressed by id, so the
 * caller is told to create it rather than having one invented under a guessed name.
 */
internal fun <T> List<DaemonRuleNode<T>>.upsertRule(
    rule: T,
    groupId: String?,
    idOf: (T) -> String,
): List<DaemonRuleNode<T>>? {
    val id = idOf(rule)
    if (groupId != null && groupId.isNotEmpty() && none { it.group?.id == groupId }) return null
    val current = groupIdByRule(idOf)[id]
    val stayingPut = groupId == null || groupId == current.orEmpty()
    if (stayingPut && findRule(id, idOf) != null) {
        return map { node -> node.copy(rules = node.rules.map { if (idOf(it) == id) rule else it }) }
    }
    val without = removeRule(id, idOf)
    val target = groupId.orEmpty()
    if (target.isEmpty()) return without + DaemonRuleNode(rules = listOf(rule))
    return without.map { node ->
        if (node.group?.id == target) node.copy(rules = node.rules + rule) else node
    }
}

/** Drops [id] wherever it sits. An emptied group stays — deleting rules is not deleting the group. */
internal fun <T> List<DaemonRuleNode<T>>.removeRule(id: String, idOf: (T) -> String): List<DaemonRuleNode<T>> =
    mapNotNull { node ->
        val kept = node.rules.filterNot { idOf(it) == id }
        when {
            kept.size == node.rules.size -> node
            node.group == null -> null
            else -> node.copy(rules = kept)
        }
    }

/** Creates the group, or renames/re-gates an existing one in place so its rules keep their priority. */
internal fun <T> List<DaemonRuleNode<T>>.upsertGroup(group: DaemonRuleGroup): List<DaemonRuleNode<T>> =
    if (none { it.group?.id == group.id }) {
        this + DaemonRuleNode(group = group)
    } else {
        map { node -> if (node.group?.id == group.id) node.copy(group = group) else node }
    }

/**
 * Deletes the group. Its rules are kept as loose rules in place unless [withRules], which is the
 * destructive form — a group whose whole point was to be toggled and removed as one unit.
 */
internal fun <T> List<DaemonRuleNode<T>>.removeGroup(
    id: String,
    withRules: Boolean,
): List<DaemonRuleNode<T>>? {
    if (none { it.group?.id == id }) return null
    return flatMap { node ->
        when {
            node.group?.id != id -> listOf(node)
            withRules -> emptyList()
            else -> node.rules.map { DaemonRuleNode(rules = listOf(it)) }
        }
    }
}

/**
 * Whether a rule in [group] matches at all: an off group gates every rule under it while each rule keeps
 * its own remembered state (ADR-0026/0030).
 *
 * This resolution has to happen on the daemon. What the engine and the devices see must be right with no
 * window open (invariant #2), so a frontend cannot be the thing that decides a disabled group is closed.
 */
internal fun effectiveEnabled(ruleEnabled: Boolean, group: DaemonRuleGroup?): Boolean =
    ruleEnabled && (group?.enabled ?: true)
