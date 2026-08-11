package com.venbiasa.wailo.shared

/**
 * The query box's little language (ADR-0054): a flat list of whitespace-separated terms, all AND-ed.
 * There is deliberately no term-level `or` and no parentheses — disjunction exists only *inside* a
 * value (`status:(500 or 503)`), which covers the "is one of" case without turning the box into an
 * expression language nobody can scan at a glance.
 *
 *     term  := '-'? (field op value | word)
 *     op    := ':' | '=' | '!=' | '>' | '>=' | '<' | '<='
 *     value := word | '"' text '"' | '/' regex '/' | '(' value (('or' | ',') value)* ')'
 *
 * A bare word keeps the original filter box's behaviour: a case-insensitive substring across the URL,
 * method and status code at once. `-` negates any term; `!=` is sugar for a negated `=`.
 *
 * Nothing here reports errors. A term that doesn't parse degrades instead of failing, so the list is
 * never blanked by a half-typed query — see [parseQuery].
 */

// Which spellings name a field in the box. `status` and `client` get the alias people reach for first;
// the rest are their own token. Matching is case-insensitive.
private val FieldTokens: Map<String, FilterKey> = buildMap {
    FilterKey.entries.forEach { put(it.token, it) }
    put("code", FilterKey.StatusCode)
    put("app", FilterKey.Client)
}

private const val RegexDelimiter = '/'

/** The operators, longest first so `>=` is never mistaken for `>` followed by a stray `=`. */
private val Operators = listOf("!=", ">=", "<=", ":", "=", ">", "<")

/**
 * Parses [query] into AND-ed terms. Never throws and never reports a syntax error: an unrecognised
 * field or a malformed value degrades to a plain keyword term (the user may genuinely be searching for
 * a word with a colon in it), and a field/operator pair whose value hasn't been typed yet is dropped
 * entirely rather than becoming a keyword that matches nothing. Together those mean every prefix of a
 * query the user is midway through typing still leaves a useful list on screen.
 */
internal fun parseQuery(query: String): List<QueryTerm> {
    if (query.isBlank()) return emptyList()
    val atoms = scan(query)
    val terms = mutableListOf<QueryTerm>()
    var i = 0
    while (i < atoms.size) {
        val head = atoms[i]
        val op = atoms.getOrNull(i + 1)?.takeIf { it.kind == AtomKind.Operator }
        if (op == null) {
            terms += keyword(query, head, head)
            i += 1
            continue
        }
        val valueAtom = atoms.getOrNull(i + 2)?.takeIf { it.kind != AtomKind.Operator }
        val negated = head.text.startsWith("-") && head.text.length > 1
        val key = FieldTokens[head.text.removePrefix("-").lowercase()]
        val clause = if (key == null || valueAtom == null) {
            null
        } else {
            buildClause(key, op.text, valueAtom, negated)
        }
        when {
            // Not a field at all, so the colon was probably just part of what they meant to search for.
            key == null -> terms += keyword(query, head, valueAtom ?: op)
            clause != null -> terms += QueryTerm.Clause(clause)
            // A real field whose value isn't usable yet — still being typed, or nonsense for this
            // comparison. Constrain nothing rather than match nothing, so the list stays useful.
            else -> Unit
        }
        i += if (valueAtom != null) 3 else 2
    }
    // A term with nothing in it (a lone quote, say) would otherwise match every row for no reason.
    return terms.filterNot { it is QueryTerm.Keyword && it.text.isBlank() }
}

/**
 * The whole span from [from] to [to] as one keyword term, so an unrecognised `foo:bar` searches for
 * `foo:bar` — and so does a bare `https://host/path`, whose colon splits it into atoms it never had to
 * be split into. A lone quoted atom is the one case that uses its text instead of its span: the quotes
 * are how the user asked for the spaces inside, not part of what they're searching for.
 */
private fun keyword(source: String, from: Atom, to: Atom): QueryTerm.Keyword {
    if (from === to && from.kind == AtomKind.Quoted) return QueryTerm.Keyword(from.text)
    val raw = source.substring(from.start, to.end)
    val negated = raw.startsWith("-") && raw.length > 1
    return QueryTerm.Keyword(if (negated) raw.substring(1) else raw, negated)
}

/**
 * Turns one `field op value` into a clause, or null if the pair makes no sense (a numeric comparison
 * against a boolean, say) and the term is better read as text.
 *
 * The operator picks the base matcher and the value's *shape* can promote it: a `/…/` value is a
 * regex and an unquoted value containing `*` is a wildcard, under `:` as much as under `=`. Someone
 * who types a `*` means a wildcard, and reading it literally would be a silent surprise. Quoting a
 * value opts out of both, which is the escape hatch for searching for a literal asterisk or slash.
 */
private fun buildClause(key: FilterKey, op: String, value: Atom, negatedByPrefix: Boolean): FilterClause? {
    val base = when (op) {
        ":" -> FilterMatcher.Contains
        "=", "!=" -> FilterMatcher.Equals
        ">" -> FilterMatcher.Gt
        ">=" -> FilterMatcher.Gte
        "<" -> FilterMatcher.Lt
        "<=" -> FilterMatcher.Lte
        else -> return null
    }
    val negated = negatedByPrefix != (op == "!=")
    val parts = when (value.kind) {
        AtomKind.Group -> splitGroup(value.text)
        AtomKind.Quoted -> listOf(QuotedPart(value.text))
        else -> listOf(barePart(value.text))
    }
    if (parts.isEmpty()) return null
    // Edited is a plain boolean with a two-value space, so only equality reads sensibly; anything else
    // (a substring of "true", a comparison) would be an accident rather than an intent.
    if (key == FilterKey.Edited) {
        if (base != FilterMatcher.Contains && base != FilterMatcher.Equals) return null
        return FilterClause(key, FilterMatcher.Equals, parts.map { it.text }, negated)
    }
    val matcher = when {
        base.isNumeric -> base
        parts.any { it is RegexPart } -> FilterMatcher.Regex
        parts.any { it is GlobPart } -> FilterMatcher.Wildcard
        else -> base
    }
    return FilterClause(key, matcher, parts.map { it.text }, negated)
}

// The shapes a single value can take, which is what decides whether the clause's matcher gets promoted
// past the one its operator asked for.
private sealed interface ValuePart {
    val text: String
}

private class QuotedPart(override val text: String) : ValuePart

private class RegexPart(override val text: String) : ValuePart

private class GlobPart(override val text: String) : ValuePart

private class PlainPart(override val text: String) : ValuePart

/**
 * Classifies an unquoted value. A regex is delimited by slashes at *both* ends: URLs are full of
 * slashes, so `url:/api/v1` has to stay a literal path fragment, and only the closing delimiter tells
 * the two apart.
 */
private fun barePart(text: String): ValuePart = when {
    text.length >= 2 && text.first() == RegexDelimiter && text.last() == RegexDelimiter ->
        RegexPart(text.substring(1, text.length - 1))
    text.contains('*') -> GlobPart(text)
    else -> PlainPart(text)
}

/** Splits a `(a or b, c)` group on `or` and commas. Quoted elements keep their literal reading. */
private fun splitGroup(inner: String): List<ValuePart> {
    val parts = mutableListOf<ValuePart>()
    var i = 0
    val buffer = StringBuilder()
    var quoted = false
    fun flush() {
        val raw = buffer.toString().trim()
        val wasQuoted = quoted
        buffer.clear()
        quoted = false
        if (raw.isEmpty()) return
        parts += if (wasQuoted) QuotedPart(raw) else barePart(raw)
    }
    while (i < inner.length) {
        val c = inner[i]
        when {
            c == '"' -> {
                val close = inner.indexOf('"', i + 1)
                val end = if (close == -1) inner.length else close
                buffer.append(inner, i + 1, end)
                quoted = true
                i = end + 1
            }
            c == ',' -> {
                flush()
                i += 1
            }
            // `or` only separates when it stands alone, so a value like "sensor" survives intact.
            c.isWhitespace() && inner.startsWith("or", i + 1, ignoreCase = true) &&
                (i + 3 >= inner.length || inner[i + 3].isWhitespace()) -> {
                flush()
                i += 3
            }
            else -> {
                buffer.append(c)
                i += 1
            }
        }
    }
    flush()
    return parts
}

/** A span of [query] the box can offer to complete, and what to offer for it. */
internal data class QueryCompletion(
    /** Where the replaceable text starts, so accepting a suggestion can splice it back in. */
    val start: Int,
    /** How much of it has been typed. */
    val prefix: String,
    /** The field whose values to offer, or null while the field *name* is what's being typed. */
    val key: FilterKey?,
    /** Which value pool the operator implies — only [FilterKey.Url] has more than one. */
    val matcher: FilterMatcher,
)

/**
 * What can be completed at the end of [query]. Only the last term is a candidate, because the box hands
 * us a plain string with no caret: the caret is always at the end as far as this can tell.
 *
 * Null means "offer nothing", which covers the deliberate cases as well as the impossible ones: a term
 * already finished with a space, a value under a field nobody recognises, and a value the user is
 * *authoring* rather than picking — a quoted string, a wildcard, a regex.
 *
 * The returned [QueryCompletion.prefix] is always exactly `query.substring(start)`, which is what lets
 * the caller splice an accepted suggestion in by truncating at `start`.
 */
internal fun completionAt(query: String): QueryCompletion? {
    if (query.isEmpty()) return null
    val atoms = scan(query)
    val last = atoms.lastOrNull() ?: return null
    // Straight after an operator the value is the obvious next thing to pick, trailing space or not.
    if (last.kind == AtomKind.Operator) {
        val key = fieldAt(atoms, atoms.size - 2) ?: return null
        return QueryCompletion(query.length, "", key, poolMatcher(last.text))
    }
    val op = atoms.getOrNull(atoms.size - 2)?.takeIf { it.kind == AtomKind.Operator }
    // An unclosed group is still collecting values — including right after an `or `, which is why this
    // comes before the trailing-space rule. Complete the value being typed, not the whole set.
    if (op != null && last.kind == AtomKind.Group && query.indexOf(')', last.start) < 0) {
        val key = fieldAt(atoms, atoms.size - 3) ?: return null
        var start = last.start + 1
        val comma = query.lastIndexOf(',')
        if (comma >= start) start = comma + 1
        val or = query.lastIndexOf(" or ", ignoreCase = true)
        if (or >= start) start = or + 4
        while (start < query.length && query[start].isWhitespace()) start += 1
        return QueryCompletion(start, query.substring(start), key, poolMatcher(op.text))
    }
    // Everywhere else a trailing space means the term is finished, and completing it would pop a menu
    // open after every word the user types.
    if (query.last().isWhitespace()) return null
    if (op == null) {
        if (last.kind != AtomKind.Word) return null
        // `-` prefixes the term, not the field name being completed.
        val start = if (last.text.startsWith("-")) last.start + 1 else last.start
        return QueryCompletion(start, query.substring(start), null, FilterMatcher.Contains)
    }
    val key = fieldAt(atoms, atoms.size - 3) ?: return null
    // A quoted, wildcard or regex value is authored, not picked out of the values already seen.
    if (last.kind != AtomKind.Word) return null
    if (last.text.contains('*') || last.text.startsWith(RegexDelimiter)) return null
    return QueryCompletion(last.start, last.text, key, poolMatcher(op.text))
}

private fun fieldAt(atoms: List<Atom>, index: Int): FilterKey? {
    val atom = atoms.getOrNull(index)?.takeIf { it.kind == AtomKind.Word } ?: return null
    return FieldTokens[atom.text.removePrefix("-").lowercase()]
}

// `:` looks inside a value while every other operator compares whole ones — for URLs that is the
// difference between offering hosts and offering entire URLs.
private fun poolMatcher(op: String): FilterMatcher =
    if (op == ":") FilterMatcher.Contains else FilterMatcher.Equals

private enum class AtomKind { Word, Operator, Quoted, Group }

// One lexical unit plus its span in the source, so a term that fails to parse can be handed back as the
// exact text the user typed rather than a reassembled approximation of it.
private class Atom(val kind: AtomKind, val text: String, val start: Int, val end: Int)

/**
 * Splits the query into atoms. Whitespace separates them but does not delimit *terms* — the grammar
 * does — which is what lets `status >= 400` and `status>=400` mean the same thing.
 */
private fun scan(source: String): List<Atom> {
    val atoms = mutableListOf<Atom>()
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            c.isWhitespace() -> i += 1
            c == '"' -> {
                val close = source.indexOf('"', i + 1)
                val end = if (close == -1) source.length else close
                atoms += Atom(AtomKind.Quoted, source.substring(i + 1, end), i, minOf(end + 1, source.length))
                i = end + 1
            }
            c == '(' -> {
                val close = source.indexOf(')', i + 1)
                val end = if (close == -1) source.length else close
                atoms += Atom(AtomKind.Group, source.substring(i + 1, end), i, minOf(end + 1, source.length))
                i = end + 1
            }
            // A value runs to the next space and nothing else. URLs are made of the very characters that
            // are operators elsewhere (`https://…`, `?a=b`), so breaking on them here would chop every
            // URL value into pieces at its scheme. One operator per term is all the grammar needs.
            atoms.lastOrNull()?.kind == AtomKind.Operator -> {
                val start = i
                while (i < source.length && !source[i].isWhitespace()) i += 1
                atoms += Atom(AtomKind.Word, source.substring(start, i), start, i)
            }
            else -> {
                val op = Operators.firstOrNull { source.startsWith(it, i) }
                if (op != null) {
                    atoms += Atom(AtomKind.Operator, op, i, i + op.length)
                    i += op.length
                } else {
                    val start = i
                    while (i < source.length && !source[i].isWhitespace() && !isAtomBreak(source, i)) i += 1
                    atoms += Atom(AtomKind.Word, source.substring(start, i), start, i)
                }
            }
        }
    }
    return atoms
}

// Where a bare word has to stop. Operators end one, so `url:orders` is three atoms; quotes and groups
// end one so `status:(500 or 503)` keeps its group intact. A lone `!` is not an operator, so it stays
// part of the word it sits in.
private fun isAtomBreak(source: String, index: Int): Boolean =
    source[index] == '"' || source[index] == '(' || Operators.any { source.startsWith(it, index) }
