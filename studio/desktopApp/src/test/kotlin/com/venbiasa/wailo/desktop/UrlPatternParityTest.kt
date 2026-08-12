package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.firstMatch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Host and shared intentionally cannot depend on each other, but Seed patterns move between their
 * frontends. This integration test is the guardrail that their duplicated matchers stay equivalent.
 */
class UrlPatternParityTest {

    @Test
    fun hostAndDesktopSeedMatchersAgree() {
        val cases = listOf(
            Case("", "https://example.com", false),
            Case("*", "https://example.com/path", true),
            Case("https://example.com/exact", "https://example.com/exact", true),
            Case("https://example.com/exact", "https://example.com/exactly", false),
            Case("https://example.com/*/items/*", "https://example.com/v1/items/42", true),
            Case("https://example.com/*/items/*", "https://example.com/v1/orders/42", false),
            Case("https://example.com/a?b=*", "https://example.com/a?b=1", true),
            Case("https://example.com/a.b/*", "https://example.com/axb/1", false),
            Case("https://EXAMPLE.com/*", "https://example.com/1", false),
        )

        cases.forEachIndexed { index, case ->
            val shared = listOf(SeedRuleDef(id = "seed-$index", urlPattern = case.pattern))
                .firstMatch(case.url, "GET") != null
            val host = urlPatternMatches(case.pattern, case.url)
            assertEquals(case.expected, host, "host matcher: $case")
            assertEquals(shared, host, "host/shared parity: $case")
        }
    }

    private data class Case(val pattern: String, val url: String, val expected: Boolean)
}
