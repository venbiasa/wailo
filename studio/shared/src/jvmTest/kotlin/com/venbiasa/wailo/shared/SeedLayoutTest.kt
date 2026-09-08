package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeedLayoutTest {

    private fun seed(
        id: String,
        enabled: Boolean = true,
        url: String = "https://x/$id",
        method: String = "",
        statusCode: Int = 200,
        delayMillis: Int = 0,
        headers: List<ResponseHeader> = emptyList(),
    ) = SeedRuleDef(
        id = id,
        enabled = enabled,
        urlPattern = url,
        method = method,
        statusCode = statusCode,
        delayMillis = delayMillis,
        headers = headers,
    )

    private fun group(id: String, name: String = id, enabled: Boolean = true) = RuleGroup(id, name, enabled)

    @Test
    fun codecRoundTripsInterleavedGroupsAndEmptyGroup() {
        val nodes: List<SeedNode> = listOf(
            RuleNode(seed("s1", url = "https://a/*", method = "GET")),
            GroupNode(
                group("g1", name = "Checkout", enabled = false),
                listOf(
                    seed(
                        "s2",
                        url = "https://a/pay",
                        method = "POST",
                        statusCode = 402,
                        delayMillis = 1_250,
                        headers = listOf(ResponseHeader("Content-Type", "application/json"), ResponseHeader("X-Trace", "abc")),
                    ),
                    seed("s3", enabled = false),
                ),
            ),
            GroupNode(group("g2", name = "Empty"), emptyList()),
            RuleNode(seed("s4")),
        )
        assertEquals(nodes, SeedLayoutCodec.decode(SeedLayoutCodec.encode(nodes)))
    }

    @Test
    fun codecRoundTripsFieldsThatCollideWithTheDelimiters() {
        // Every free-text field is Base64-encoded precisely so a pattern or header value containing the
        // codec's own separators can't split a line into the wrong fields.
        val nodes: List<SeedNode> = listOf(
            RuleNode(
                seed(
                    "s1",
                    url = "https://a/*?q=a|b,c:d\ne",
                    headers = listOf(ResponseHeader("X-Odd", "a|b,c:d")),
                ),
            ),
        )
        assertEquals(nodes, SeedLayoutCodec.decode(SeedLayoutCodec.encode(nodes)))
    }

    @Test
    fun codecEmptyStringIsEmptyLayout() {
        assertEquals(emptyList(), SeedLayoutCodec.decode(""))
        assertEquals("", SeedLayoutCodec.encode(emptyList()))
    }

    @Test
    fun aPreDelayLineDefaultsToNoDelay() {
        val current = SeedLayoutCodec.encode(listOf(RuleNode(seed("s1", delayMillis = 500))))
        val preDelay = current.substringBeforeLast('|')

        assertEquals(0, SeedLayoutCodec.decode(preDelay).findRule("s1")?.delayMillis)
    }

    @Test
    fun fillTakesEnabledSeedsFlattenedInOrder() {
        // What Fill arms: rulesForMatch drops off-groups and off-rules and flattens the rest to one level,
        // which is exactly "don't bring the group, keep the order" (ADR-0041).
        val nodes: List<SeedNode> = listOf(
            RuleNode(seed("s1")),
            GroupNode(group("g1", enabled = false), listOf(seed("s2"))),
            GroupNode(group("g2"), listOf(seed("s3", enabled = false), seed("s4"))),
            RuleNode(seed("s5")),
        )
        assertEquals(listOf("s1", "s4", "s5"), nodes.rulesForMatch().map { it.id })
    }

    @Test
    fun groupedSeedsReorderLikeAnyOtherRuleList() {
        val nodes: List<SeedNode> = listOf(
            RuleNode(seed("s1")),
            GroupNode(group("g1"), listOf(seed("s2"))),
        )
        val moved = nodes.moveRule("s1", InGroupAt("g1", 0))
        assertEquals(listOf("s1", "s2"), moved.groupNode("g1")!!.rules.map { it.id })
        assertNull(moved.groupOf("nope"))
    }
}
