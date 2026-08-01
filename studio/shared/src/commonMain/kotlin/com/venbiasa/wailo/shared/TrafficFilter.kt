package com.venbiasa.wailo.shared

/**
 * The traffic list's view filter: a non-destructive lens over already-captured [FlowEntry] rows. It is
 * deliberately distinct from the device-side CaptureFilter (which drops traffic at the source, never to
 * be seen) — this only hides rows the desktop already has. It's transient view state the viewer owns and
 * rebuilds on every change, so it never persists and needs no host/engine plumbing.
 *
 * A free-text [query] plus any number of structured [clauses], all AND-ed. [query] is the quick keyword
 * box; each clause is one "field contains value" test the user builds through `+ Add filter`. An empty
 * query and no clauses means "match everything", so a default filter is inert.
 */
internal data class TrafficFilter(
    val query: String = "",
    val clauses: List<FilterClause> = emptyList(),
) {
    val isActive: Boolean
        get() = query.isNotBlank() || clauses.isNotEmpty()
}

/**
 * The fields a structured [FilterClause] can target — exactly the columns the traffic list already
 * derives per row (see `TrafficList`), so a value that reads on a row is a value the filter can match.
 * [label] is what the `+ Add filter` key dropdown shows.
 */
internal enum class FilterKey(val label: String) {
    Method("Method"),
    Url("URL"),
    StatusCode("Status Code"),
    Client("Client"),
    Edited("Edited"),
}

/**
 * One structured criterion: [key]'s value must contain [value] (case-insensitive substring). [Edited] is
 * the one exception — it has no free text, so its [value] is parsed as a boolean ("true"/"false").
 */
internal data class FilterClause(val key: FilterKey, val value: String)

/**
 * Whether [entry] passes the filter. Derives method/status/url/client exactly as the traffic row does
 * (see `TrafficList`'s row binding) so a row can never be shown yet filtered out on a value that
 * disagrees with what the list displays. All gates are AND-ed; an empty filter passes everything.
 */
internal fun TrafficFilter.matches(entry: FlowEntry): Boolean {
    val exchange = entry.exchange
    val method = exchange.request?.method?.ifEmpty { "?" } ?: "?"
    val code = exchange.response?.code
    val codeText = code?.toString().orEmpty()
    val url = exchange.request?.url ?: ""
    val client = entry.appId

    if (query.isNotBlank()) {
        val haystacks = listOf(url, method, codeText)
        // Whitespace-separated terms are AND-ed: each must appear in at least one haystack.
        val ok = query.trim().split(Regex("\\s+"))
            .all { term -> haystacks.any { it.contains(term, ignoreCase = true) } }
        if (!ok) return false
    }

    // Every clause is an independent AND gate: a "contains" substring test, except Edited (a boolean).
    return clauses.all { clause ->
        when (clause.key) {
            FilterKey.Method -> method.contains(clause.value, ignoreCase = true)
            FilterKey.Url -> url.contains(clause.value, ignoreCase = true)
            FilterKey.StatusCode -> codeText.contains(clause.value, ignoreCase = true)
            FilterKey.Client -> client.contains(clause.value, ignoreCase = true)
            FilterKey.Edited -> entry.edited == clause.value.equals("true", ignoreCase = true)
        }
    }
}
