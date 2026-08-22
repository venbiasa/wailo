package com.venbiasa.wailo.shared

/**
 * The traffic list's view filter: a non-destructive lens over already-captured [FlowEntry] rows. It is
 * deliberately distinct from the device-side CaptureFilter (which drops traffic at the source, never to
 * be seen) — this only hides rows the desktop already has. It's transient view state the viewer owns and
 * rebuilds on every change, so it never persists and needs no host/engine plumbing.
 *
 * Two independent surfaces, AND-ed together (ADR-0054): [query] is the typed query box, parsed by
 * [parseQuery] into terms; [clauses] are the structured pills built through `+ Add filter`. They share
 * the matcher semantics below but not a syntax, so neither has to serialize into the other. An empty
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
 * The fields a [FilterClause] can target — exactly the columns the traffic list already derives per row
 * (see `TrafficList`), so a value that reads on a row is a value the filter can match. [label] is what
 * the `+ Add filter` key dropdown shows; [token] is how the field is spelled in the query box.
 */
internal enum class FilterKey(val label: String, val token: String) {
    Method("Method", "method"),
    Url("URL", "url"),
    StatusCode("Status Code", "status"),
    Client("Client", "client"),
    Edited("Edited", "edited"),
    Proxy("Proxy", "proxy"),
    ;

    /**
     * A field whose whole value space is true/false. Named rather than tested field-by-field because
     * four separate places have to agree on it — the matchers offered, whether several values make
     * sense, what the query parser accepts, and what the add-filter card will commit.
     */
    val isBoolean: Boolean get() = this == Edited || this == Proxy
}

/**
 * How a clause compares its field to its value. [label] is the affirmative reading, [negatedLabel] the
 * inverse — the two are spelled out rather than derived because English has real opposites for the
 * string matchers ("contains" / "does not contain") that read far better than a "not" prefix.
 *
 * The numeric matchers keep a "not" prefix on purpose: `not (status > 400)` and `status <= 400` are
 * *not* the same predicate for a row with no response code yet, so labelling the negation as its
 * apparent mirror would make two different filters look identical.
 */
internal enum class FilterMatcher(val label: String, val negatedLabel: String) {
    Contains("contains", "does not contain"),
    Equals("is", "is not"),
    Wildcard("matches", "does not match"),
    Regex("matches regex", "does not match regex"),
    Gt(">", "not >"),
    Gte(">=", "not >="),
    Lt("<", "not <"),
    Lte("<=", "not <="),
    ;

    val isNumeric: Boolean get() = this == Gt || this == Gte || this == Lt || this == Lte
}

/**
 * One structured criterion: [key] compared to [values] under [matcher], inverted by [negated].
 *
 * [values] is a list so one clause can express "is one of" — it matches when *any* value matches, which
 * under [negated] correctly becomes "none of". An empty list is inert (matches everything) rather than
 * matching nothing, so a half-built clause can never blank the list.
 */
internal data class FilterClause(
    val key: FilterKey,
    val matcher: FilterMatcher,
    val values: List<String>,
    val negated: Boolean = false,
) {
    constructor(key: FilterKey, matcher: FilterMatcher, value: String, negated: Boolean = false) :
        this(key, matcher, listOf(value), negated)
}

/** One entry in the add-filter matcher dropdown: a matcher plus the polarity it is offered in. */
internal data class MatcherOption(val matcher: FilterMatcher, val negated: Boolean) {
    val label: String get() = if (negated) matcher.negatedLabel else matcher.label
}

/**
 * The matchers the add-filter modal offers for a field, in menu order — the first is that field's
 * default. Numeric comparisons are offered only where the value is genuinely a number, and only
 * affirmatively (see [FilterMatcher] on why their negations aren't their mirrors). A boolean field has
 * a two-value space, so "is" is the only sensible test.
 */
internal val FilterKey.matcherOptions: List<MatcherOption>
    get() = when {
        isBoolean -> listOf(MatcherOption(FilterMatcher.Equals, negated = false))
        this == FilterKey.StatusCode -> listOf(
            MatcherOption(FilterMatcher.Equals, negated = false),
            MatcherOption(FilterMatcher.Equals, negated = true),
            MatcherOption(FilterMatcher.Gte, negated = false),
            MatcherOption(FilterMatcher.Gt, negated = false),
            MatcherOption(FilterMatcher.Lte, negated = false),
            MatcherOption(FilterMatcher.Lt, negated = false),
            MatcherOption(FilterMatcher.Contains, negated = false),
        )
        else -> listOf(
            MatcherOption(FilterMatcher.Contains, negated = false),
            MatcherOption(FilterMatcher.Contains, negated = true),
            MatcherOption(FilterMatcher.Equals, negated = false),
            MatcherOption(FilterMatcher.Equals, negated = true),
            MatcherOption(FilterMatcher.Wildcard, negated = false),
            MatcherOption(FilterMatcher.Wildcard, negated = true),
            MatcherOption(FilterMatcher.Regex, negated = false),
            MatcherOption(FilterMatcher.Regex, negated = true),
        )
    }

/** The matcher a freshly picked field starts on: substring for text, exact for numbers and booleans. */
internal val FilterKey.defaultMatcher: MatcherOption
    get() = matcherOptions.first()

/** Whether a field's value field should accept several values ("is one of"). Numbers and booleans don't. */
internal val FilterKey.acceptsMultipleValues: Boolean
    get() = !isBoolean

/**
 * How a clause reads on its pill. A single value keeps the plain verb ("URL contains orders"); several
 * values switch to the set reading ("Method is one of GET, POST"), where a negation means *none* of
 * them matched rather than "not any one of them".
 */
internal fun FilterClause.label(): String {
    val verb = when {
        values.size <= 1 -> if (negated) matcher.negatedLabel else matcher.label
        matcher == FilterMatcher.Equals -> if (negated) "is none of" else "is one of"
        else -> matcher.label + if (negated) " none of" else " any of"
    }
    return "${key.label} $verb ${values.joinToString(", ")}"
}

/**
 * One AND-ed unit of a parsed query. A [Clause] is the same structured criterion a pill carries; a
 * [Keyword] is a bare word, which keeps the original filter box's behaviour — a case-insensitive
 * substring across the URL, method and status code at once.
 */
internal sealed interface QueryTerm {
    data class Keyword(val text: String, val negated: Boolean = false) : QueryTerm

    data class Clause(val clause: FilterClause) : QueryTerm
}

/**
 * Builds the row predicate once for this filter, so patterns are compiled per *filter change* rather
 * than per row: a `Regex` rebuilt inside the row loop would be recompiled once for every captured
 * exchange (up to the retention cap) on every keystroke's worth of typing.
 *
 * The query box's terms and the pills' clauses are AND-ed into one flat list — the two surfaces are
 * independent inputs to the same evaluator, which is what keeps them behaving identically without
 * sharing a syntax.
 */
internal fun TrafficFilter.compile(): (FlowEntry) -> Boolean {
    val terms = parseQuery(query) + clauses.map { QueryTerm.Clause(it) }
    if (terms.isEmpty()) return { true }
    val predicates = terms.map { it.compile() }
    return { entry ->
        val row = RowValues(entry)
        predicates.all { it(row) }
    }
}

/**
 * Whether [entry] passes the filter. Compiles per call, so it's for one-off checks and tests — the
 * viewer holds a [compile]d predicate across the whole list instead.
 */
internal fun TrafficFilter.matches(entry: FlowEntry): Boolean = compile()(entry)

/**
 * The field values a row is matched on, derived exactly as `TrafficList`'s row binding derives them so
 * a row can never be shown yet filtered out on a value that disagrees with what the list displays.
 * [code] keeps the numeric form for comparisons; a row with no response yet has none.
 */
private class RowValues(entry: FlowEntry) {
    val method: String = entry.exchange.request?.method?.ifEmpty { "?" } ?: "?"
    val url: String = entry.exchange.request?.url ?: ""
    val code: Int? = entry.exchange.response?.code
    val codeText: String = code?.toString().orEmpty()
    val client: String = entry.appId
    val edited: Boolean = entry.edited
    val proxy: Boolean = entry.viaProxy

    fun text(key: FilterKey): String = when (key) {
        FilterKey.Method -> method
        FilterKey.Url -> url
        FilterKey.StatusCode -> codeText
        FilterKey.Client -> client
        FilterKey.Edited -> edited.toString()
        FilterKey.Proxy -> proxy.toString()
    }
}

private fun QueryTerm.compile(): (RowValues) -> Boolean = when (this) {
    is QueryTerm.Keyword -> {
        val text = this.text
        val negated = this.negated
        { row ->
            val hit = row.url.contains(text, ignoreCase = true) ||
                row.method.contains(text, ignoreCase = true) ||
                row.codeText.contains(text, ignoreCase = true)
            hit != negated
        }
    }
    is QueryTerm.Clause -> clause.compile()
}

private fun FilterClause.compile(): (RowValues) -> Boolean {
    // A clause with nothing to compare against constrains nothing, so a half-built one can't blank the
    // list. Checked before the value predicates so `any {}`'s false-on-empty never leaks through.
    if (values.isEmpty()) return { true }
    val key = this.key
    val negated = this.negated
    val tests = values.map { matcher.test(it) }
    return { row ->
        val hit = tests.any { it(row, key) }
        hit != negated
    }
}

/**
 * Compiles one matcher/value pair into a test over a row's field. Patterns are built here — once, when
 * the filter changes — and only the match itself runs per row.
 */
private fun FilterMatcher.test(value: String): (RowValues, FilterKey) -> Boolean {
    // Both patterns are built up front so the per-row lambdas below only ever run a match. An unusable
    // one stays null and matches nothing rather than everything: a broken wildcard that silently widened
    // to "any row" would be the opposite of what the user asked for.
    val glob = if (this == FilterMatcher.Wildcard) globRegex(value) else null
    val regex = if (this == FilterMatcher.Regex) {
        runCatching { Regex(value, RegexOption.IGNORE_CASE) }.getOrNull()
    } else {
        null
    }
    return when (this) {
        FilterMatcher.Contains -> { row, key -> row.text(key).contains(value, ignoreCase = true) }
        FilterMatcher.Equals -> { row, key -> row.text(key).equals(value, ignoreCase = true) }
        FilterMatcher.Wildcard -> { row, key -> glob != null && glob.matches(row.text(key)) }
        FilterMatcher.Regex -> { row, key -> regex != null && regex.containsMatchIn(row.text(key)) }
        FilterMatcher.Gt -> numericTest(value) { code, bound -> code > bound }
        FilterMatcher.Gte -> numericTest(value) { code, bound -> code >= bound }
        FilterMatcher.Lt -> numericTest(value) { code, bound -> code < bound }
        FilterMatcher.Lte -> numericTest(value) { code, bound -> code <= bound }
    }
}

/**
 * A comparison against the row's numeric status code. A row with no response yet has no code, so it
 * fails every comparison — it is genuinely neither above nor below a bound. Numeric matchers are only
 * offered for Status Code, so any other field simply has no number to compare and never matches.
 */
private fun numericTest(value: String, compare: (Int, Int) -> Boolean): (RowValues, FilterKey) -> Boolean {
    val bound = value.trim().toIntOrNull() ?: return { _, _ -> false }
    return { row, key ->
        val code = if (key == FilterKey.StatusCode) row.code else row.text(key).toIntOrNull()
        code != null && compare(code, bound)
    }
}

/**
 * A whole-string `*` glob, case-insensitive. Deliberately the same scheme the device rules use
 * (`sdk-android`'s `WailoMatching.kt`, mirrored desktop-side by `seedUrlMatches`) so a pattern copied
 * from a Map Local or breakpoint rule into the filter selects the traffic that rule would act on.
 * Case-insensitive here, unlike those: this is a view lens the user types by hand, not a wire contract.
 * Null for a pattern with nothing to match on.
 */
private fun globRegex(pattern: String): Regex? {
    if (pattern.isEmpty()) return null
    val body = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    return runCatching { Regex(body, RegexOption.IGNORE_CASE) }.getOrNull()
}
