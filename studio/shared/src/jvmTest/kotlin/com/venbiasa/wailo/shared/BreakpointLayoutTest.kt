package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BreakpointLayoutTest {

    private fun rule(
        id: String,
        enabled: Boolean = true,
        url: String = "https://x/$id",
        method: String = "",
        onRequest: Boolean = true,
        onResponse: Boolean = false,
    ) = BreakpointRuleDef(id = id, enabled = enabled, urlPattern = url, method = method, onRequest = onRequest, onResponse = onResponse)

    private fun group(id: String, name: String = id, enabled: Boolean = true) = RuleGroup(id, name, enabled)

    // A loose rule, a group with two rules, then another loose rule — the interleaved shape the UI allows.
    private fun sample(): List<BreakpointNode> = listOf(
        RuleNode(rule("r1")),
        GroupNode(group("g1"), listOf(rule("r2"), rule("r3"))),
        RuleNode(rule("r4")),
    )

    @Test
    fun rulesForMatchDropsOffGroupsAndOffRulesButKeepsPriority() {
        val nodes = listOf(
            RuleNode(rule("r1")),
            GroupNode(group("g1", enabled = false), listOf(rule("r2"))),
            GroupNode(group("g2"), listOf(rule("r3", enabled = false), rule("r4"))),
        )
        // g1 off -> r2 out; g2 on -> r3 (off) out, r4 in. Priority order preserved.
        assertEquals(listOf("r1", "r4"), nodes.rulesForMatch().map { it.id })
    }

    @Test
    fun moveRuleIntoGroupWorksForBreakpointRules() {
        val moved = sample().moveRule("r1", InGroupAt("g1", 0))
        assertEquals(listOf("r1", "r2", "r3"), moved.groupNode("g1")!!.rules.map { it.id })
        assertNull(moved.groupOf("r4")) // r4 still loose
    }

    @Test
    fun codecRoundTripsInterleavedGroupsAndEmptyGroup() {
        val nodes: List<BreakpointNode> = listOf(
            RuleNode(rule("r1", url = "https://a/*", method = "GET")),
            GroupNode(
                group("g1", name = "Auth", enabled = false),
                listOf(
                    rule("r2", url = "https://a/login", method = "POST", onRequest = true, onResponse = true),
                    rule("r3", onRequest = false, onResponse = true),
                ),
            ),
            GroupNode(group("g2", name = "Empty"), emptyList()),
            RuleNode(rule("r4")),
        )
        assertEquals(nodes, BreakpointLayoutCodec.decode(BreakpointLayoutCodec.encode(nodes)))
    }

    @Test
    fun codecEmptyStringIsEmptyLayout() {
        assertEquals(emptyList(), BreakpointLayoutCodec.decode(""))
        assertEquals("", BreakpointLayoutCodec.encode(emptyList()))
    }

    @Test
    fun legacyFlatLineDecodesAsLooseRule() {
        // A pre-groups breakpoint line is exactly a rule's fields with no G/R/C prefix; simulate by
        // stripping the "R|" the loose encoder adds. The field order is identical, so it migrates cleanly.
        val flat = BreakpointLayoutCodec.encode(listOf(RuleNode(rule("rL", url = "https://legacy/*", method = "POST", onResponse = true))))
            .removePrefix("R|")
        val decoded = BreakpointLayoutCodec.decode(flat)
        assertEquals(1, decoded.size)
        assertEquals("rL", decoded.single().id)
        val migrated = decoded.findRule("rL")!!
        assertEquals("https://legacy/*", migrated.urlPattern)
        assertEquals("POST", migrated.method)
        assertEquals(true, migrated.onResponse)
        assertNull(decoded.groupOf("rL"))
    }
}
