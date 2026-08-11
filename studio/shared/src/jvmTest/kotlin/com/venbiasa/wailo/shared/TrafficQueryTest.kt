package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficQueryTest {

    private fun clause(
        key: FilterKey,
        matcher: FilterMatcher,
        vararg values: String,
        negated: Boolean = false,
    ) = QueryTerm.Clause(FilterClause(key, matcher, values.toList(), negated))

    private fun keyword(text: String, negated: Boolean = false) = QueryTerm.Keyword(text, negated)

    @Test
    fun blankQueryHasNoTerms() {
        assertEquals(emptyList(), parseQuery(""))
        assertEquals(emptyList(), parseQuery("   "))
    }

    @Test
    fun bareWordsBecomeKeywordTerms() {
        assertEquals(listOf(keyword("orders"), keyword("201")), parseQuery("orders 201"))
    }

    @Test
    fun aFieldAndAValueBecomeAClause() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "orders")),
            parseQuery("url:orders"),
        )
    }

    @Test
    fun fieldTokensAreCaseInsensitiveAndHaveAliases() {
        assertEquals(
            listOf(clause(FilterKey.StatusCode, FilterMatcher.Contains, "500")),
            parseQuery("STATUS:500"),
        )
        assertEquals(parseQuery("status:500"), parseQuery("code:500"))
        assertEquals(parseQuery("client:foo"), parseQuery("app:foo"))
    }

    @Test
    fun equalsIsExactAndBangEqualsNegatesIt() {
        assertEquals(
            listOf(clause(FilterKey.Method, FilterMatcher.Equals, "GET")),
            parseQuery("method=GET"),
        )
        assertEquals(
            listOf(clause(FilterKey.Method, FilterMatcher.Equals, "GET", negated = true)),
            parseQuery("method!=GET"),
        )
    }

    @Test
    fun minusNegatesAnyTerm() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "analytics", negated = true)),
            parseQuery("-url:analytics"),
        )
        assertEquals(listOf(keyword("checkout", negated = true)), parseQuery("-checkout"))
        // Two negations cancel, so `-x != y` is plain equality rather than a double-negative surprise.
        assertEquals(
            listOf(clause(FilterKey.Method, FilterMatcher.Equals, "GET")),
            parseQuery("-method!=GET"),
        )
    }

    @Test
    fun aHyphenInsideAWordIsNotANegation() {
        assertEquals(listOf(keyword("x-trace-id")), parseQuery("x-trace-id"))
    }

    @Test
    fun spacingAroundAnOperatorDoesNotMatter() {
        val expected = listOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "400"))
        assertEquals(expected, parseQuery("status>=400"))
        assertEquals(expected, parseQuery("status >= 400"))
        assertEquals(expected, parseQuery("status>= 400"))
        assertEquals(expected, parseQuery("status >=400"))
    }

    @Test
    fun everyComparisonOperatorParses() {
        assertEquals(listOf(clause(FilterKey.StatusCode, FilterMatcher.Gt, "400")), parseQuery("status > 400"))
        assertEquals(listOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "400")), parseQuery("status >= 400"))
        assertEquals(listOf(clause(FilterKey.StatusCode, FilterMatcher.Lt, "400")), parseQuery("status < 400"))
        assertEquals(listOf(clause(FilterKey.StatusCode, FilterMatcher.Lte, "400")), parseQuery("status <= 400"))
    }

    @Test
    fun aStarPromotesTheValueToAWildcardUnderEitherOperator() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "https://api.example.com/*")),
            parseQuery("url = https://api.example.com/*"),
        )
        // Typing a star always means a wildcard; reading it literally under `:` would be a silent surprise.
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "*orders*")),
            parseQuery("url:*orders*"),
        )
    }

    @Test
    fun slashesAtBothEndsMakeARegex() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Regex, "orders\\d+")),
            parseQuery("url:/orders\\d+/"),
        )
    }

    @Test
    fun aPathValueStaysLiteralBecauseItDoesNotCloseTheDelimiter() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "/api/v1")),
            parseQuery("url:/api/v1"),
        )
    }

    @Test
    fun aUrlValueKeepsItsSchemeAndQueryString() {
        // The colon and equals inside a URL are the very characters that are operators elsewhere; one
        // operator per term is what keeps them from chopping the value up.
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "https://api.example.com/v1?a=b")),
            parseQuery("url:https://api.example.com/v1?a=b"),
        )
    }

    @Test
    fun aBareUrlKeepsItsColonsAsOneKeyword() {
        assertEquals(listOf(keyword("https://api.example.com")), parseQuery("https://api.example.com"))
    }

    @Test
    fun quotingKeepsSpacesAndTurnsOffPatternMeaning() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "GET /v1")),
            parseQuery("url:\"GET /v1\""),
        )
        // A quoted star is an asterisk, which is the escape hatch out of wildcard promotion.
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "a*b")),
            parseQuery("url:\"a*b\""),
        )
    }

    @Test
    fun aLoneQuotedTermIsAKeywordWithoutItsQuotes() {
        assertEquals(listOf(keyword("GET /v1")), parseQuery("\"GET /v1\""))
    }

    @Test
    fun aGroupIsSeveralValuesForOneClause() {
        assertEquals(
            listOf(clause(FilterKey.Method, FilterMatcher.Contains, "GET", "POST")),
            parseQuery("method:(GET or POST)"),
        )
        assertEquals(
            listOf(clause(FilterKey.StatusCode, FilterMatcher.Equals, "500", "503")),
            parseQuery("status = (500, 503)"),
        )
        assertEquals(
            parseQuery("status = (500 or 503)"),
            parseQuery("status = (500, 503)"),
        )
    }

    @Test
    fun orOnlySeparatesWhenItStandsAlone() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "sensor", "north")),
            parseQuery("url:(sensor or north)"),
        )
        // "or" inside a word is part of that word, not a separator.
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "orders")),
            parseQuery("url:(orders)"),
        )
    }

    @Test
    fun editedOnlyEverComparesForEquality() {
        // `:` would otherwise be a substring test, and "tru" would quietly pass for "true".
        assertEquals(
            listOf(clause(FilterKey.Edited, FilterMatcher.Equals, "true")),
            parseQuery("edited:true"),
        )
        assertEquals(
            listOf(clause(FilterKey.Edited, FilterMatcher.Equals, "false")),
            parseQuery("edited = false"),
        )
    }

    @Test
    fun aComparisonAgainstEditedConstrainsNothing() {
        // Edited is a boolean, so ">" has nothing to mean. Dropping the term keeps the list on screen;
        // reading it as text would have matched nothing and looked like a broken filter.
        assertEquals(emptyList(), parseQuery("edited > 1"))
    }

    // --- graceful degradation: nothing here may throw, and nothing may blank the list mid-word ---

    @Test
    fun anUnknownFieldFallsBackToTheTextTheUserTyped() {
        assertEquals(listOf(keyword("foo:bar")), parseQuery("foo:bar"))
        assertEquals(listOf(keyword("foo:bar"), keyword("baz")), parseQuery("foo:bar baz"))
    }

    @Test
    fun aFieldWithNoValueYetConstrainsNothing() {
        assertEquals(emptyList(), parseQuery("url:"))
        assertEquals(emptyList(), parseQuery("status >="))
        // The finished terms around it still apply, so the list narrows as far as it can.
        assertEquals(listOf(keyword("orders")), parseQuery("orders url:"))
    }

    @Test
    fun halfTypedInputNeverThrows() {
        val partials = listOf(
            "s", "st", "sta", "status", "status ", "status >", "status >=", "status >= ", "status >= 4",
            "url:\"unterminated", "url:(500 or", "url:/unclosed", "-", "-url", "-url:", "!=", ":", "()",
            "url = ", "method:(", "url:*", "\"", "((()))", "a:b:c", "url::x",
        )
        partials.forEach { partial ->
            val terms = parseQuery(partial)
            assertTrue(terms.size < 8, "\"$partial\" exploded into ${terms.size} terms")
        }
    }

    @Test
    fun anUnterminatedQuoteStillFiltersOnWhatWasTyped() {
        assertEquals(
            listOf(clause(FilterKey.Url, FilterMatcher.Contains, "orders")),
            parseQuery("url:\"orders"),
        )
    }

    @Test
    fun anUnclosedGroupStillFiltersOnWhatWasTyped() {
        assertEquals(
            listOf(clause(FilterKey.StatusCode, FilterMatcher.Contains, "500", "503")),
            parseQuery("status:(500 or 503"),
        )
    }

    @Test
    fun severalTermsOfEveryKindCombine() {
        assertEquals(
            listOf(
                clause(FilterKey.StatusCode, FilterMatcher.Gte, "400"),
                clause(FilterKey.Url, FilterMatcher.Contains, "analytics", negated = true),
                clause(FilterKey.Method, FilterMatcher.Contains, "GET", "POST"),
                keyword("checkout"),
            ),
            parseQuery("status >= 400 -url:analytics method:(GET or POST) checkout"),
        )
    }

    // What the query box offers as you type. `start` is the index a picked suggestion is spliced at, so
    // every case checks it: getting it wrong corrupts the query rather than merely offering the wrong list.

    private fun completion(query: String) = completionAt(query)

    private fun assertCompletes(
        query: String,
        prefix: String,
        key: FilterKey?,
        matcher: FilterMatcher = FilterMatcher.Contains,
    ) {
        val at = completionAt(query) ?: error("\"$query\" offered no completion")
        assertEquals(prefix, at.prefix, "prefix for \"$query\"")
        assertEquals(key, at.key, "field for \"$query\"")
        assertEquals(matcher, at.matcher, "pool for \"$query\"")
        // The prefix has to be exactly what `start` points at, or accepting a suggestion duplicates or
        // eats characters around the caret.
        assertEquals(prefix, query.substring(at.start), "span for \"$query\"")
    }

    @Test
    fun aPartialWordCompletesToAFieldName() {
        assertCompletes("st", prefix = "st", key = null)
        assertCompletes("-ur", prefix = "ur", key = null)
    }

    @Test
    fun nothingIsOfferedForAnEmptyOrFinishedTerm() {
        assertEquals(null, completion(""))
        assertEquals(null, completion("checkout "))
        assertEquals(null, completion("url:orders "))
    }

    @Test
    fun anOperatorCompletesToThatFieldsValues() {
        assertCompletes("method:", prefix = "", key = FilterKey.Method)
        assertCompletes("method:GE", prefix = "GE", key = FilterKey.Method)
        assertCompletes("-client:com.ex", prefix = "com.ex", key = FilterKey.Client)
    }

    @Test
    fun spacesAroundTheOperatorDontHideTheField() {
        assertCompletes("status >= 4", prefix = "4", key = FilterKey.StatusCode, matcher = FilterMatcher.Equals)
        assertCompletes("status : 4", prefix = "4", key = FilterKey.StatusCode)
    }

    @Test
    fun anExactOperatorAsksForTheWholeValuePool() {
        // Only Url has two pools (hosts vs whole URLs), and `:` is the one that wants hosts.
        assertCompletes("url:api", prefix = "api", key = FilterKey.Url, matcher = FilterMatcher.Contains)
        assertCompletes("url=https://a", prefix = "https://a", key = FilterKey.Url, matcher = FilterMatcher.Equals)
    }

    @Test
    fun anOpenGroupCompletesTheValueBeingTypedNotTheWholeSet() {
        assertCompletes("method:(GET or PO", prefix = "PO", key = FilterKey.Method)
        assertCompletes("method:(GET or ", prefix = "", key = FilterKey.Method)
        assertCompletes("method:(GET,PO", prefix = "PO", key = FilterKey.Method)
        assertCompletes("method:(", prefix = "", key = FilterKey.Method)
        // Closed: the set is finished, so there is nothing left to complete inside it.
        assertEquals(null, completion("method:(GET or POST)"))
    }

    @Test
    fun anUnknownFieldIsOfferedNothing() {
        assertEquals(null, completion("foo:bar"))
    }

    @Test
    fun anAuthoredPatternIsNotCompleted() {
        // A wildcard, a regex or a quoted literal is written, not picked out of a list of seen values.
        assertEquals(null, completion("url=https://api.example.com/*"))
        assertEquals(null, completion("url:/orders"))
        assertEquals(null, completion("url:\"a b"))
    }

    @Test
    fun completingNeverThrowsOnAHalfTypedQuery() {
        val partials = listOf(
            "s", "st", "status", "status ", "status >", "status >=", "status >= ", "status >= 4",
            "url:\"unterminated", "url:(500 or", "url:/unclosed", "-", "-url", "-url:", "!=", ":", "()",
            "url = ", "method:(", "url:*", "\"", "((()))", "a:b:c", "url::x", "  ",
        )
        partials.forEach { partial ->
            val at = completionAt(partial)
            if (at != null) {
                assertTrue(
                    at.start in 0..partial.length,
                    "\"$partial\" pointed at ${at.start}, outside the query",
                )
                assertEquals(at.prefix, partial.substring(at.start), "span for \"$partial\"")
            }
        }
    }
}
