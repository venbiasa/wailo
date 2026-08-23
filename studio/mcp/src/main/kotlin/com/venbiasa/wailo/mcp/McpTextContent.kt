package com.venbiasa.wailo.mcp

/**
 * Renders a tool's result into the text content of its MCP reply.
 *
 * Every tool answers with a one-line summary plus a structured payload, and for a long time only the
 * summary reached the client: the payload went out as `structuredContent` alone. That field is an optional,
 * later addition to MCP, and a client that reads text content — which is the universal path, and what most
 * agents actually consume — saw `50 exchange(s)` and never a single row. The traffic was visible only as a
 * count of itself, which is indistinguishable from the access gate being off.
 *
 * The text is rendered from the very same map that goes out as structured content, never from the domain
 * objects, so the two can never disagree and — the part that matters — redaction cannot be bypassed here:
 * whatever `redact_secrets` withheld upstream is already absent from this map (ADR-0059).
 */
internal fun mcpTextContent(response: McpToolResponse): String {
    // A failure's payload is just its own message; rendering it would print the same sentence twice.
    if (response.isError) return response.text
    val body = renderMcpData(response.data)
    return if (body.isEmpty()) response.text else "${response.text}\n$body"
}

/**
 * The payload as indented `key: value` lines, YAML-shaped because that is the most readable form per
 * character and needs no quoting rules to be understood.
 *
 * Nulls and empty collections are dropped. They are the majority of a summary row — most exchanges have no
 * error, most requests no body — and an absent key reads the same as an empty one while costing nothing.
 * The structured payload still carries them for anything counting fields.
 */
internal fun renderMcpData(data: Map<String, Any?>): String {
    val out = StringBuilder()
    out.appendEntries(data, depth = 0)
    return out.toString().trimEnd('\n')
}

private const val INDENT = "  "

private fun StringBuilder.appendEntries(map: Map<*, *>, depth: Int) {
    for ((key, value) in map) appendEntry(key.toString(), value, depth)
}

private fun StringBuilder.appendEntry(key: String, value: Any?, depth: Int) {
    val pad = INDENT.repeat(depth)
    when {
        value == null -> Unit
        value is Map<*, *> -> {
            if (value.isEmpty()) return
            append(pad).append(key).append(":\n")
            appendEntries(value, depth + 1)
        }
        value is List<*> -> {
            if (value.isEmpty()) return
            append(pad).append(key).append(":\n")
            for (item in value) appendItem(item, depth + 1)
        }
        else -> appendScalar(key, value.toString(), pad)
    }
}

private fun StringBuilder.appendItem(item: Any?, depth: Int) {
    val pad = INDENT.repeat(depth)
    if (item is Map<*, *>) {
        // The dash sits on the first field and the rest align under it, so where one row ends and the next
        // begins stays obvious without counting indentation.
        var first = true
        for ((key, value) in item) {
            if (value == null || (value is Map<*, *> && value.isEmpty()) || (value is List<*> && value.isEmpty())) {
                continue
            }
            val marker = if (first) "$pad- " else "$pad  "
            first = false
            when (value) {
                is Map<*, *> -> {
                    append(marker).append(key).append(":\n")
                    appendEntries(value, depth + 2)
                }
                is List<*> -> {
                    append(marker).append(key).append(":\n")
                    for (nested in value) appendItem(nested, depth + 2)
                }
                else -> appendScalar(key.toString(), value.toString(), marker, continuation = "$pad  ")
            }
        }
        if (first) append(pad).append("- {}\n")
    } else {
        append(pad).append("- ").append(item?.toString().orEmpty()).append('\n')
    }
}

/**
 * One `key: value`, or a `|` block when the value has newlines in it — which a captured body very often
 * does, and which flattening would make unreadable at exactly the moment it matters most.
 */
private fun StringBuilder.appendScalar(key: String, text: String, prefix: String, continuation: String = prefix) {
    if ('\n' !in text) {
        append(prefix).append(key).append(": ").append(text).append('\n')
        return
    }
    append(prefix).append(key).append(": |\n")
    for (line in text.lineSequence()) append(continuation).append(INDENT).append(line).append('\n')
}
