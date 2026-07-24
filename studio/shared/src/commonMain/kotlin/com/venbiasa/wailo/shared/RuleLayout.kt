package com.venbiasa.wailo.shared

import kotlin.random.Random

/**
 * A rule that can live in a grouped [LayoutNode] layout. The pure layout ops below need only three
 * things from a rule — its [id], its [enabled] flag, and a way to return a copy with a new [enabled]
 * ([withEnabled]) — so they can reorder, group, and gate any rule type without knowing its concrete
 * shape. F-bounded (`T : LayoutRule<T>`) so [withEnabled] returns the concrete rule, not the interface:
 * Map Local's `MapLocalRuleDef` and the breakpoints' `BreakpointRuleDef` both implement it and share the
 * whole group/reorder machinery (ADR-0026's Map Local grouping, generalized so ADR-0027 reuses it).
 */
interface LayoutRule<T : LayoutRule<T>> {
    val id: String
    val enabled: Boolean
    fun withEnabled(enabled: Boolean): T
}

/**
 * A single-level group: a named container the author can toggle as a unit. Groups never nest (ADR-0026).
 * [enabled] gates its rules for matching — a rule in an off group never matches, and its own switch reads
 * disabled in the list while keeping its remembered state. [name] defaults to "New group" and is not part
 * of matching.
 */
data class RuleGroup(
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
 * One top-level entry in a grouped rule layout: either a loose (ungrouped) rule or a group with its
 * ordered rules. The layout is a single ordered `List<LayoutNode<T>>` where groups and loose rules
 * interleave; that top-to-bottom order, flattened, is the match priority — the first active rule that
 * matches wins (the device matches first-in-list, ADR-0026). It is the host's owned, persisted state;
 * `shared` renders it and calls back with a new layout to mutate (ADR-0013).
 */
sealed interface LayoutNode<T : LayoutRule<T>> {
    val id: String
}

data class RuleNode<T : LayoutRule<T>>(val rule: T) : LayoutNode<T> {
    override val id: String get() = rule.id
}

data class GroupNode<T : LayoutRule<T>>(val group: RuleGroup, val rules: List<T>) : LayoutNode<T> {
    override val id: String get() = group.id
}

/** Where a dragged rule/group is dropped, resolved by the list UI against the layout minus the dragged item. */
sealed interface LayoutDropTarget

/** Insert as a top-level node at [index] (0..topLevelCount). Used for loose rules and whole groups. */
data class TopLevelAt(val index: Int) : LayoutDropTarget

/** Insert a rule as a child of [groupId] at child [index] (0..group.rules.size). */
data class InGroupAt(val groupId: String, val index: Int) : LayoutDropTarget

// --- Read helpers -----------------------------------------------------------------------------------

/** Every rule in layout (priority) order, regardless of enabled state. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.allRules(): List<T> = buildList {
    this@allRules.forEach { node ->
        when (node) {
            is RuleNode -> add(node.rule)
            is GroupNode -> addAll(node.rules)
        }
    }
}

/** The rule with [ruleId] anywhere in the layout, or null. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.findRule(ruleId: String): T? = allRules().firstOrNull { it.id == ruleId }

/** The group holding [ruleId], or null if the rule is loose / not found. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.groupOf(ruleId: String): RuleGroup? {
    forEach { node -> if (node is GroupNode && node.rules.any { it.id == ruleId }) return node.group }
    return null
}

/** The [GroupNode] with [groupId], or null — used by the list UI to read a group's rules by id. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.groupNode(groupId: String): GroupNode<T>? {
    forEach { node -> if (node is GroupNode && node.group.id == groupId) return node }
    return null
}

/**
 * Whether [ruleId] is active for matching: its group must be on (or it is loose) AND the rule itself on.
 * This is the effective-enabled the device sees — a rule in an off group is inactive even if its own
 * switch is on.
 */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.isRuleActive(ruleId: String): Boolean {
    forEach { node ->
        when (node) {
            is RuleNode -> if (node.rule.id == ruleId) return node.rule.enabled
            is GroupNode -> node.rules.firstOrNull { it.id == ruleId }?.let { return node.group.enabled && it.enabled }
        }
    }
    return false
}

/** Active rules in priority order (group-on AND rule-on), for compiling the device match-set (ADR-0026). */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.rulesForMatch(): List<T> = buildList {
    this@rulesForMatch.forEach { node ->
        when (node) {
            is RuleNode -> if (node.rule.enabled) add(node.rule)
            is GroupNode -> if (node.group.enabled) node.rules.forEach { if (it.enabled) add(it) }
        }
    }
}

// --- Mutations (pure; each returns a new layout) ----------------------------------------------------

/** Replace [rule] in place by id (loose or grouped); if it isn't present yet, append it as a loose rule. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.upsertRule(rule: T): List<LayoutNode<T>> {
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
fun <T : LayoutRule<T>> List<LayoutNode<T>>.removeRule(ruleId: String): List<LayoutNode<T>> = map { node ->
    when (node) {
        is RuleNode -> node
        is GroupNode -> node.copy(rules = node.rules.filterNot { it.id == ruleId })
    }
}.filterNot { it is RuleNode && it.rule.id == ruleId }

/** Remove a group AND its rules (delete-all, ADR-0026). */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.removeGroup(groupId: String): List<LayoutNode<T>> =
    filterNot { it is GroupNode && it.group.id == groupId }

/** Append a new (empty) group at the end of the top level. */
fun <T : LayoutRule<T>> List<LayoutNode<T>>.addGroup(group: RuleGroup): List<LayoutNode<T>> = this + GroupNode(group, emptyList())

fun <T : LayoutRule<T>> List<LayoutNode<T>>.setGroupEnabled(groupId: String, enabled: Boolean): List<LayoutNode<T>> =
    map { if (it is GroupNode && it.group.id == groupId) it.copy(group = it.group.copy(enabled = enabled)) else it }

fun <T : LayoutRule<T>> List<LayoutNode<T>>.renameGroup(groupId: String, name: String): List<LayoutNode<T>> =
    map { if (it is GroupNode && it.group.id == groupId) it.copy(group = it.group.copy(name = name)) else it }

fun <T : LayoutRule<T>> List<LayoutNode<T>>.setRuleEnabled(ruleId: String, enabled: Boolean): List<LayoutNode<T>> = map { node ->
    when (node) {
        is RuleNode -> if (node.rule.id == ruleId) RuleNode(node.rule.withEnabled(enabled)) else node
        is GroupNode -> if (node.rules.any { it.id == ruleId }) {
            node.copy(rules = node.rules.map { if (it.id == ruleId) it.withEnabled(enabled) else it })
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
fun <T : LayoutRule<T>> List<LayoutNode<T>>.moveRule(ruleId: String, target: LayoutDropTarget): List<LayoutNode<T>> {
    val rule = findRule(ruleId) ?: return this
    return removeRule(ruleId).insertRule(rule, target)
}

private fun <T : LayoutRule<T>> List<LayoutNode<T>>.insertRule(rule: T, target: LayoutDropTarget): List<LayoutNode<T>> =
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
fun <T : LayoutRule<T>> List<LayoutNode<T>>.moveGroup(groupId: String, index: Int): List<LayoutNode<T>> {
    val node = firstOrNull { it is GroupNode && it.group.id == groupId } ?: return this
    return filterNot { it.id == groupId }.toMutableList().apply { add(index.coerceIn(0, size), node) }
}
