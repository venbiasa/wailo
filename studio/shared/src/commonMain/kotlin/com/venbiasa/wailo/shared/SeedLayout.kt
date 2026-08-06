package com.venbiasa.wailo.shared

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The seed layout is a grouped rule layout ([LayoutNode]/[RuleGroup] in `RuleLayout.kt`) over
 * [SeedRuleDef] — the same group/reorder machinery Map Local and the breakpoints panel use
 * (ADR-0026/0028). Groups organize the panel only: filling the breakpoint window's queue flattens them
 * away, keeping just the order (ADR-0041).
 */
typealias SeedNode = LayoutNode<SeedRuleDef>

/**
 * Serializes a seed layout to/from a single string for the host's primitive key-value store, mirroring
 * [MapLocalLayoutCodec]. One line per entry, `|`-separated fields, each free-text field Base64-encoded so
 * it can never collide with the delimiters. Line kinds: `G` = group header, `C` = a child rule of the
 * current group, `R` = a loose (top-level) rule.
 *
 * Unlike the Map Local and breakpoint codecs there is no legacy prefix-less branch: this key is new, so
 * no unprefixed lines were ever written. A line whose field count doesn't parse is dropped rather than
 * failing the whole load, which is what keeps a future field addition backward-compatible.
 */
@OptIn(ExperimentalEncodingApi::class)
object SeedLayoutCodec {
    private const val LINE_SEP = "\n"
    private const val FIELD_SEP = "|"
    private const val HEADER_SEP = ","
    private const val HEADER_KV = ":"
    private const val TYPE_GROUP = "G"
    private const val TYPE_CHILD = "C"
    private const val TYPE_LOOSE = "R"

    fun encode(nodes: List<SeedNode>): String = buildList {
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

    fun decode(text: String): List<SeedNode> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<SeedNode>()
        var pendingGroup: RuleGroup? = null
        val pendingRules = mutableListOf<SeedRuleDef>()
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
                else -> flushGroup()
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

    private fun encodeRule(rule: SeedRuleDef): String = listOf(
        rule.id,
        if (rule.enabled) "1" else "0",
        enc(rule.urlPattern),
        enc(rule.method),
        rule.statusCode.toString(),
        encodeHeaders(rule.headers),
    ).joinToString(FIELD_SEP)

    private fun decodeRule(parts: List<String>): SeedRuleDef? {
        if (parts.size < 6) return null
        return SeedRuleDef(
            id = parts[0],
            enabled = parts[1] == "1",
            urlPattern = dec(parts[2]),
            method = dec(parts[3]),
            statusCode = parts[4].toIntOrNull() ?: 200,
            headers = decodeHeaders(parts[5]),
        )
    }

    private fun encodeHeaders(headers: List<ResponseHeader>): String =
        headers.filter { it.name.isNotBlank() }
            .joinToString(HEADER_SEP) { enc(it.name) + HEADER_KV + enc(it.value) }

    private fun decodeHeaders(field: String): List<ResponseHeader> {
        if (field.isBlank()) return emptyList()
        return field.split(HEADER_SEP).mapNotNull { part ->
            val kv = part.split(HEADER_KV)
            if (kv.size != 2) null else ResponseHeader(dec(kv[0]), dec(kv[1]))
        }
    }

    private fun enc(value: String): String = Base64.encode(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
}
