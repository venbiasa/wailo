package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrafficFilterTest {

    // A captured row with sensible defaults; each test overrides only what it exercises. A null [code]
    // means no response yet (Pending), unless [error] is set (Failed) — mirroring the row's derivation.
    private fun entry(
        method: String = "GET",
        url: String = "https://api.example.com/users",
        code: Int? = 200,
        error: String = "",
        appId: String = "com.example.app",
        platform: String = "android",
        edited: Boolean = false,
    ): FlowEntry = FlowEntry(
        deviceName = "Pixel",
        appId = appId,
        platform = platform,
        exchange = HttpExchange(
            id = "$method $url $code",
            request = HttpRequest(method = method, url = url),
            response = code?.let { HttpResponse(code = it) },
            error = error,
            edited = edited,
        ),
        edited = edited,
    )

    private fun filterOf(vararg clauses: FilterClause) = TrafficFilter(clauses = clauses.toList())

    private fun clause(
        key: FilterKey,
        matcher: FilterMatcher,
        vararg values: String,
        negated: Boolean = false,
    ) = FilterClause(key, matcher, values.toList(), negated)

    @Test
    fun emptyFilterMatchesEverythingAndIsInactive() {
        val filter = TrafficFilter()
        assertFalse(filter.isActive)
        assertTrue(filter.matches(entry()))
        assertTrue(filter.matches(entry(method = "POST", code = 500, error = "boom")))
    }

    @Test
    fun isActiveReflectsQueryOrClauses() {
        assertFalse(TrafficFilter().isActive)
        assertTrue(TrafficFilter(query = "x").isActive)
        assertTrue(filterOf(clause(FilterKey.Method, FilterMatcher.Contains, "GET")).isActive)
    }

    // --- the query box, whose bare words must keep behaving exactly as the pre-language keyword box ---

    @Test
    fun keywordTermsAreAndedAcrossUrlMethodAndCode() {
        val e = entry(method = "POST", url = "https://api.example.com/orders", code = 201)
        assertTrue(TrafficFilter(query = "orders").matches(e))
        assertTrue(TrafficFilter(query = "POST").matches(e)) // case-insensitive, matches method
        assertTrue(TrafficFilter(query = "201").matches(e)) // matches the status code
        assertTrue(TrafficFilter(query = "orders 201").matches(e)) // every term must match somewhere
        assertFalse(TrafficFilter(query = "orders 500").matches(e)) // one term misses
        assertFalse(TrafficFilter(query = "users").matches(e))
    }

    @Test
    fun queryClausesFilterTheSameWayPillsDo() {
        val e = entry(method = "POST", url = "https://api.example.com/orders", code = 503)
        assertTrue(TrafficFilter(query = "url:orders").matches(e))
        assertTrue(TrafficFilter(query = "status >= 500").matches(e))
        assertTrue(TrafficFilter(query = "method = POST").matches(e))
        assertFalse(TrafficFilter(query = "-url:orders").matches(e))
        assertFalse(TrafficFilter(query = "status < 500").matches(e))
    }

    @Test
    fun negatedKeywordExcludesTheRowsItMatches() {
        val e = entry(url = "https://api.example.com/analytics")
        assertFalse(TrafficFilter(query = "-analytics").matches(e))
        assertTrue(TrafficFilter(query = "-checkout").matches(e))
    }

    // --- matchers ---

    @Test
    fun containsIsACaseInsensitiveSubstring() {
        val post = entry(method = "post") // lowercase on the wire
        assertTrue(filterOf(clause(FilterKey.Method, FilterMatcher.Contains, "POST")).matches(post))
        assertTrue(filterOf(clause(FilterKey.Method, FilterMatcher.Contains, "os")).matches(post))
        assertFalse(filterOf(clause(FilterKey.Method, FilterMatcher.Contains, "GET")).matches(post))
    }

    @Test
    fun equalsIsTheWholeValueNotASubstring() {
        val e = entry(url = "https://api.example.com/orders", code = 503)
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Equals, "HTTPS://API.EXAMPLE.COM/orders")).matches(e))
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Equals, "orders")).matches(e))
        // The old contains-only behaviour let "5" stand in for every 5xx; exact match no longer does.
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Equals, "503")).matches(e))
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Equals, "5")).matches(e))
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Contains, "5")).matches(e))
    }

    @Test
    fun negationInvertsAClause() {
        val e = entry(url = "https://api.example.com/orders")
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Contains, "orders", negated = true)).matches(e))
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Contains, "users", negated = true)).matches(e))
    }

    @Test
    fun wildcardIsAnchoredToTheWholeValue() {
        val e = entry(url = "https://api.example.com/orders/42")
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "https://api.example.com/*")).matches(e))
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "*orders*")).matches(e))
        // Anchored, so a bare fragment that "contains" would have matched does not.
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "orders")).matches(e))
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "https://other.com/*")).matches(e))
    }

    @Test
    fun wildcardTreatsEverythingButTheStarAsLiteral() {
        // A dot is a regex metacharacter but only a dot here, so a glob can't quietly widen.
        val e = entry(url = "https://apiXexample.com/orders")
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "https://api.example.com/*")).matches(e))
    }

    @Test
    fun regexMatchesAnywhereInTheValue() {
        val e = entry(url = "https://api.example.com/orders/42")
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Regex, "orders/\\d+")).matches(e))
        assertTrue(filterOf(clause(FilterKey.Url, FilterMatcher.Regex, "^https")).matches(e))
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Regex, "orders/[a-z]+")).matches(e))
    }

    @Test
    fun anUnusablePatternMatchesNothingRatherThanEverything() {
        val e = entry()
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Regex, "orders(")).matches(e))
        assertFalse(filterOf(clause(FilterKey.Url, FilterMatcher.Wildcard, "")).matches(e))
    }

    @Test
    fun numericComparisonsUseTheStatusCode() {
        val serverError = entry(code = 503)
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "500")).matches(serverError))
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gt, "500")).matches(serverError))
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Lt, "500")).matches(serverError))
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Lte, "503")).matches(serverError))
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gt, "503")).matches(serverError))
    }

    @Test
    fun aRowWithNoResponseIsNeitherAboveNorBelowABound() {
        val pending = entry(code = null)
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "400")).matches(pending))
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Lt, "400")).matches(pending))
        // Which is exactly why the negation of ">= 400" is not "< 400": it keeps the pending row.
        assertTrue(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "400", negated = true)).matches(pending))
    }

    @Test
    fun aComparisonAgainstSomethingUnnumericMatchesNothing() {
        assertFalse(filterOf(clause(FilterKey.StatusCode, FilterMatcher.Gte, "abc")).matches(entry(code = 503)))
        assertFalse(filterOf(clause(FilterKey.Method, FilterMatcher.Gte, "400")).matches(entry()))
    }

    @Test
    fun severalValuesMatchAnyOfThem() {
        val serverError = entry(code = 503)
        val one = clause(FilterKey.StatusCode, FilterMatcher.Equals, "500", "503")
        assertTrue(filterOf(one).matches(serverError))
        assertFalse(filterOf(one).matches(entry(code = 200)))
        assertTrue(filterOf(clause(FilterKey.Method, FilterMatcher.Equals, "GET", "POST")).matches(entry(method = "POST")))
    }

    @Test
    fun negatingSeveralValuesMeansNoneOfThem() {
        val none = clause(FilterKey.Method, FilterMatcher.Equals, "GET", "POST", negated = true)
        assertFalse(filterOf(none).matches(entry(method = "GET")))
        assertFalse(filterOf(none).matches(entry(method = "POST")))
        assertTrue(filterOf(none).matches(entry(method = "DELETE")))
    }

    @Test
    fun aClauseWithNoValuesConstrainsNothing() {
        val inert = FilterClause(FilterKey.Url, FilterMatcher.Contains, emptyList())
        assertTrue(filterOf(inert).matches(entry()))
    }

    @Test
    fun clientMatchesTheAppId() {
        val foo = entry(appId = "com.foo.app")
        assertTrue(filterOf(clause(FilterKey.Client, FilterMatcher.Contains, "foo")).matches(foo))
        assertFalse(filterOf(clause(FilterKey.Client, FilterMatcher.Contains, "bar")).matches(foo))
    }

    @Test
    fun editedIsABoolean() {
        assertTrue(filterOf(clause(FilterKey.Edited, FilterMatcher.Equals, "true")).matches(entry(edited = true)))
        assertFalse(filterOf(clause(FilterKey.Edited, FilterMatcher.Equals, "true")).matches(entry(edited = false)))
        assertTrue(filterOf(clause(FilterKey.Edited, FilterMatcher.Equals, "false")).matches(entry(edited = false)))
        assertFalse(filterOf(clause(FilterKey.Edited, FilterMatcher.Equals, "false")).matches(entry(edited = true)))
    }

    // --- composition ---

    @Test
    fun clausesAndQueryAreAllAnded() {
        val e = entry(method = "GET", url = "https://api.example.com/users", code = 200)
        val filter = TrafficFilter(
            query = "users",
            clauses = listOf(
                clause(FilterKey.Method, FilterMatcher.Equals, "GET"),
                clause(FilterKey.StatusCode, FilterMatcher.Lt, "400"),
            ),
        )
        assertTrue(filter.matches(e))
        assertFalse(filter.copy(clauses = filter.clauses + clause(FilterKey.Method, FilterMatcher.Equals, "POST")).matches(e))
        assertFalse(filter.copy(query = "orders").matches(e))
    }

    @Test
    fun compiledPredicateAgreesWithASingleMatch() {
        val filter = TrafficFilter(query = "status >= 400", clauses = listOf(clause(FilterKey.Method, FilterMatcher.Equals, "GET")))
        val passes = filter.compile()
        val rows = listOf(entry(code = 500), entry(code = 200), entry(method = "POST", code = 500))
        assertEquals(rows.map { filter.matches(it) }, rows.map { passes(it) })
        assertEquals(listOf(true, false, false), rows.map { passes(it) })
    }

    // --- how a clause reads on its pill ---

    @Test
    fun clauseLabelReadsAsASentence() {
        assertEquals(
            "URL contains orders",
            clause(FilterKey.Url, FilterMatcher.Contains, "orders").label(),
        )
        assertEquals(
            "URL does not contain analytics",
            clause(FilterKey.Url, FilterMatcher.Contains, "analytics", negated = true).label(),
        )
        assertEquals(
            "Status Code >= 400",
            clause(FilterKey.StatusCode, FilterMatcher.Gte, "400").label(),
        )
        assertEquals(
            "Method is one of GET, POST",
            clause(FilterKey.Method, FilterMatcher.Equals, "GET", "POST").label(),
        )
        assertEquals(
            "Method is none of GET, POST",
            clause(FilterKey.Method, FilterMatcher.Equals, "GET", "POST", negated = true).label(),
        )
        assertEquals(
            "URL matches any of a*, b*",
            clause(FilterKey.Url, FilterMatcher.Wildcard, "a*", "b*").label(),
        )
    }

    @Test
    fun everyFieldOffersAtLeastOneMatcherAndDefaultsToItsFirst() {
        FilterKey.entries.forEach { key ->
            assertTrue(key.matcherOptions.isNotEmpty(), "${key.label} offers no matcher")
            assertEquals(key.matcherOptions.first(), key.defaultMatcher)
        }
        // A number and a boolean take one value; only text has a set to be "one of".
        assertFalse(FilterKey.Edited.acceptsMultipleValues)
        assertTrue(FilterKey.Url.acceptsMultipleValues)
    }
}
