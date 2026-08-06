package com.venbiasa.wailo.shared

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Map Local layout is a grouped rule layout ([LayoutNode]/[RuleGroup] in `RuleLayout.kt`) over
 * [MapLocalRuleDef]; these aliases keep the Map Local call sites reading in Map Local terms while the
 * group/reorder machinery and its pure ops (allRules/rulesForMatch/moveRule/…) are shared with the
 * breakpoints panel (ADR-0026 generalized). Only the persistence codec below is Map-Local-specific.
 */
typealias MapLocalNode = LayoutNode<MapLocalRuleDef>
typealias MapLocalGroup = RuleGroup

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

    private fun encodeHeaders(headers: List<ResponseHeader>): String =
        headers.filter { it.name.isNotBlank() }
            .joinToString(HEADER_SEP) { enc(it.name) + HEADER_KV + enc(it.value) }

    // A legacy line's field[6] once held a lone Base64 Content-Type; a Base64 token has no ":" separator,
    // so its absence marks that legacy shape — migrate it to a Content-Type header. Blank = no headers.
    private fun decodeHeaders(field: String): List<ResponseHeader> {
        if (field.isBlank()) return emptyList()
        if (!field.contains(HEADER_KV)) return listOf(ResponseHeader("Content-Type", dec(field)))
        return field.split(HEADER_SEP).mapNotNull { part ->
            val kv = part.split(HEADER_KV)
            if (kv.size != 2) null else ResponseHeader(dec(kv[0]), dec(kv[1]))
        }
    }

    // Base64 (RFC 4648, padded) — byte-compatible with the prior java.util.Base64 encoding, so values
    // written before this codec still decode.
    private fun enc(value: String): String = Base64.encode(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
}
