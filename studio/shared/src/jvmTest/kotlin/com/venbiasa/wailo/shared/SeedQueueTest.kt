package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The armed queue's lifecycle: Fill (`rulesForMatch`), answer (`firstMatch`), spend (`consume`). The host
 * runs these three in that order per arriving hold, so testing them composed is what pins the behaviour
 * the feature is for — a sequence of seeds answering a sequence of requests (ADR-0041).
 */
class SeedQueueTest {

    private fun seed(id: String, url: String, method: String = "", status: Int = 200, enabled: Boolean = true) =
        SeedRuleDef(id = id, enabled = enabled, urlPattern = url, method = method, statusCode = status)

    @Test
    fun spendingASeedLeavesTheRestInOrder() {
        val queue = listOf(seed("a", "https://x/a"), seed("b", "https://x/b"), seed("c", "https://x/c"))
        assertEquals(listOf("a", "c"), queue.consume(queue[1]).map { it.id })
    }

    @Test
    fun successiveIdenticalRequestsTakeSuccessiveSeeds() {
        // The point of the whole feature: two seeds for the same URL script two different answers.
        var queue = listOf(
            seed("first", "https://x/poll", status = 202),
            seed("second", "https://x/poll", status = 200),
            seed("other", "https://x/else"),
        )

        val hit1 = queue.firstMatch("https://x/poll", "GET")!!
        assertEquals(202, hit1.statusCode)
        queue = queue.consume(hit1)

        val hit2 = queue.firstMatch("https://x/poll", "GET")!!
        assertEquals(200, hit2.statusCode)
        queue = queue.consume(hit2)

        // Both spent: a third identical request finds nothing and the hold falls through to the user.
        assertNull(queue.firstMatch("https://x/poll", "GET"))
        // The unrelated seed is untouched by any of it.
        assertEquals(listOf("other"), queue.map { it.id })
    }

    @Test
    fun spendingOneSeedDoesNotDisturbAnEqualLookingSibling() {
        // Two seeds can be field-identical apart from their ids; spending is by id, so exactly one goes.
        val queue = listOf(seed("a", "https://x/same"), seed("b", "https://x/same"))
        val hit = queue.firstMatch("https://x/same", "GET")!!
        assertEquals("a", hit.id)
        assertEquals(listOf("b"), queue.consume(hit).map { it.id })
    }

    @Test
    fun fillSkipsDisabledSeedsAndDisabledGroups() {
        val nodes: List<SeedNode> = listOf(
            RuleNode(seed("off", "https://x/off", enabled = false)),
            GroupNode(RuleGroup("g-off", "Off", enabled = false), listOf(seed("in-off-group", "https://x/grouped"))),
            GroupNode(RuleGroup("g-on", "On", enabled = true), listOf(seed("in-on-group", "https://x/grouped"))),
            RuleNode(seed("on", "https://x/on")),
        )
        val queue = nodes.rulesForMatch()
        assertEquals(listOf("in-on-group", "on"), queue.map { it.id })
        // A seed that never filled can't answer, even though its pattern would have matched.
        assertEquals("in-on-group", queue.firstMatch("https://x/grouped", "GET")?.id)
        assertNull(queue.firstMatch("https://x/off", "GET"))
    }

    @Test
    fun refillingReplacesAPartlySpentQueue() {
        val nodes: List<SeedNode> = listOf(RuleNode(seed("a", "https://x/a")), RuleNode(seed("b", "https://x/b")))
        val spent = nodes.rulesForMatch().let { it.consume(it.first()) }
        assertEquals(listOf("b"), spent.map { it.id })
        // Re-Fill reads the layout again, so the spent seed is back — how a run is reset mid-session.
        assertEquals(listOf("a", "b"), nodes.rulesForMatch().map { it.id })
    }
}
