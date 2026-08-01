package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
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

    private fun clauseFilter(vararg clauses: FilterClause) = TrafficFilter(clauses = clauses.toList())

    @Test
    fun emptyFilterMatchesEverythingAndIsInactive() {
        val filter = TrafficFilter()
        assertFalse(filter.isActive)
        assertTrue(filter.matches(entry()))
        assertTrue(filter.matches(entry(method = "POST", code = 500, error = "boom")))
    }

    @Test
    fun queryTermsAreAndedAcrossUrlMethodAndCode() {
        val e = entry(method = "POST", url = "https://api.example.com/orders", code = 201)
        assertTrue(TrafficFilter(query = "orders").matches(e))
        assertTrue(TrafficFilter(query = "POST").matches(e)) // case-insensitive, matches method
        assertTrue(TrafficFilter(query = "201").matches(e)) // matches the status code
        assertTrue(TrafficFilter(query = "orders 201").matches(e)) // every term must match somewhere
        assertFalse(TrafficFilter(query = "orders 500").matches(e)) // one term misses
        assertFalse(TrafficFilter(query = "users").matches(e))
    }

    @Test
    fun methodClauseIsCaseInsensitiveContains() {
        val post = entry(method = "post") // lowercase on the wire
        assertTrue(clauseFilter(FilterClause(FilterKey.Method, "POST")).matches(post))
        assertTrue(clauseFilter(FilterClause(FilterKey.Method, "os")).matches(post)) // substring
        assertFalse(clauseFilter(FilterClause(FilterKey.Method, "GET")).matches(post))
    }

    @Test
    fun urlClauseContains() {
        val e = entry(url = "https://api.example.com/orders")
        assertTrue(clauseFilter(FilterClause(FilterKey.Url, "orders")).matches(e))
        assertTrue(clauseFilter(FilterClause(FilterKey.Url, "API.EXAMPLE")).matches(e)) // case-insensitive
        assertFalse(clauseFilter(FilterClause(FilterKey.Url, "users")).matches(e))
    }

    @Test
    fun statusCodeClauseContains() {
        assertTrue(clauseFilter(FilterClause(FilterKey.StatusCode, "500")).matches(entry(code = 500)))
        assertTrue(clauseFilter(FilterClause(FilterKey.StatusCode, "5")).matches(entry(code = 503))) // substring
        assertFalse(clauseFilter(FilterClause(FilterKey.StatusCode, "50")).matches(entry(code = 200)))
        // No response code yet (Pending) can't match a code substring.
        assertFalse(clauseFilter(FilterClause(FilterKey.StatusCode, "200")).matches(entry(code = null)))
    }

    @Test
    fun clientClauseContainsAppId() {
        val foo = entry(appId = "com.foo.app")
        assertTrue(clauseFilter(FilterClause(FilterKey.Client, "foo")).matches(foo))
        assertFalse(clauseFilter(FilterClause(FilterKey.Client, "bar")).matches(foo))
    }

    @Test
    fun editedClauseParsesBoolean() {
        assertTrue(clauseFilter(FilterClause(FilterKey.Edited, "true")).matches(entry(edited = true)))
        assertFalse(clauseFilter(FilterClause(FilterKey.Edited, "true")).matches(entry(edited = false)))
        assertTrue(clauseFilter(FilterClause(FilterKey.Edited, "false")).matches(entry(edited = false)))
        assertFalse(clauseFilter(FilterClause(FilterKey.Edited, "false")).matches(entry(edited = true)))
    }

    @Test
    fun clausesAndQueryAreAllAnded() {
        val e = entry(method = "GET", url = "https://api.example.com/users", code = 200)
        val filter = TrafficFilter(
            query = "users",
            clauses = listOf(
                FilterClause(FilterKey.Method, "GET"),
                FilterClause(FilterKey.StatusCode, "200"),
            ),
        )
        assertTrue(filter.matches(e))
        assertFalse(filter.copy(clauses = filter.clauses + FilterClause(FilterKey.Method, "POST")).matches(e))
        assertFalse(filter.copy(query = "orders").matches(e))
    }

    @Test
    fun isActiveReflectsQueryOrClauses() {
        assertFalse(TrafficFilter().isActive)
        assertTrue(TrafficFilter(query = "x").isActive)
        assertTrue(clauseFilter(FilterClause(FilterKey.Method, "GET")).isActive)
    }
}
