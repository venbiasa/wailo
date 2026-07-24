package com.venbiasa.wailo.shared

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The breakpoints layout is a grouped rule layout ([LayoutNode]/[RuleGroup] in `RuleLayout.kt`) over
 * [BreakpointRuleDef] — the same group/reorder machinery Map Local uses (ADR-0026/0027). This alias lets
 * the breakpoints call sites read in breakpoint terms; only the persistence codec below is
 * breakpoint-specific.
 */
typealias BreakpointNode = LayoutNode<BreakpointRuleDef>

/**
 * Serializes a breakpoints layout to/from a single string for the host's primitive key-value store,
 * mirroring [MapLocalLayoutCodec]. One line per entry, `|`-separated fields, each free-text field
 * Base64-encoded so it can never collide with the delimiters. Line kinds: `G` = group header, `C` = a
 * child rule of the current group, `R` = a loose (top-level) rule. A group's children are contiguous
 * right after its header, so the type prefix alone reconstructs the interleaved layout unambiguously.
 *
 * Migration: lines with no type prefix are the pre-groups format (the rule's id led the line) and decode
 * as loose rules — so breakpoint prefs written before grouping load unchanged. A rule's field order is
 * kept identical to that pre-groups format, so a legacy line is exactly a prefix-less rule line. Kept
 * pure (no filesystem, no java.*) so it lives in `shared` and is unit-tested.
 */
@OptIn(ExperimentalEncodingApi::class)
object BreakpointLayoutCodec {
    private const val LINE_SEP = "\n"
    private const val FIELD_SEP = "|"
    private const val TYPE_GROUP = "G"
    private const val TYPE_CHILD = "C"
    private const val TYPE_LOOSE = "R"

    fun encode(nodes: List<BreakpointNode>): String = buildList {
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

    fun decode(text: String): List<BreakpointNode> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<BreakpointNode>()
        var pendingGroup: RuleGroup? = null
        val pendingRules = mutableListOf<BreakpointRuleDef>()
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
                // No recognized prefix: a legacy (pre-groups) line whose fields are the rule itself.
                else -> {
                    flushGroup()
                    decodeRule(fields)?.let { result.add(RuleNode(it)) }
                }
            }
        }
        flushGroup()
        return result
    }

    private fun parseGroup(fields: List<String>): RuleGroup? {
        if (fields.size < 4) return null
        return RuleGroup(
            id = fields[1],
            name = dec(fields[3]).ifBlank { "New group" },
            enabled = fields[2] == "1",
        )
    }

    private fun encodeRule(rule: BreakpointRuleDef): String = listOf(
        rule.id,
        if (rule.enabled) "1" else "0",
        if (rule.onRequest) "1" else "0",
        if (rule.onResponse) "1" else "0",
        enc(rule.method),
        enc(rule.urlPattern),
    ).joinToString(FIELD_SEP)

    private fun decodeRule(parts: List<String>): BreakpointRuleDef? {
        if (parts.size < 6) return null
        return runCatching {
            BreakpointRuleDef(
                id = parts[0],
                enabled = parts[1] == "1",
                onRequest = parts[2] == "1",
                onResponse = parts[3] == "1",
                method = dec(parts[4]),
                urlPattern = dec(parts[5]),
            )
        }.getOrNull()
    }

    // Base64 (RFC 4648, padded) — byte-compatible with the prior BreakpointStore encoding, so values
    // written before grouping still decode.
    private fun enc(value: String): String = Base64.encode(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
}
