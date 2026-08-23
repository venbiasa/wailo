package com.venbiasa.wailo.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The placement rules the daemon now owns (ADR-0080). They are pure, so they are pinned here rather than
 * through a running daemon — the integration test only has to prove the wiring reaches them.
 */
class DaemonRuleGroupsTest {
    private fun nodes(vararg node: DaemonRuleNode<String>) = node.toList()

    private fun group(id: String, enabled: Boolean = true) = DaemonRuleNode(DaemonRuleGroup(id, id, enabled), emptyList<String>())

    private fun List<DaemonRuleNode<String>>.upsert(rule: String, groupId: String? = null) =
        upsertRule(rule, groupId) { it }

    @Test
    fun flatteningFollowsTopToBottomMatchPriority() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a", "b")),
            DaemonRuleNode(rules = listOf("c")),
            DaemonRuleNode(DaemonRuleGroup("g2"), listOf("d")),
        )

        assertEquals(listOf("a", "b", "c", "d"), layout.flattenRules())
        assertEquals(mapOf("a" to "g1", "b" to "g1", "d" to "g2"), layout.groupIdByRule { it })
        assertEquals(listOf("g1", "g2"), layout.groups().map { it.id })
    }

    @Test
    fun aNewRuleLandsLastSoItCannotShadowAnExistingOne() {
        val layout = nodes(DaemonRuleNode(rules = listOf("first"))).upsert("second")

        assertEquals(listOf("first", "second"), layout!!.flattenRules())
    }

    @Test
    fun aNewRuleWithAGroupLandsAtTheEndOfThatGroup() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a")),
            DaemonRuleNode(rules = listOf("loose")),
        ).upsert("b", groupId = "g1")

        assertEquals(listOf("a", "b", "loose"), layout!!.flattenRules())
        assertEquals("g1", layout.groupIdByRule { it }["b"])
    }

    /** An agent correcting a body must not silently change which rule wins a match. */
    @Test
    fun rewritingARuleKeepsItsPositionAndGroup() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a", "b")),
            DaemonRuleNode(rules = listOf("c")),
        ).upsert("b")

        assertEquals(listOf("a", "b", "c"), layout!!.flattenRules())
        assertEquals("g1", layout.groupIdByRule { it }["b"])
    }

    @Test
    fun namingAGroupMovesAnExistingRuleIntoIt() {
        val layout = nodes(
            group("g1"),
            DaemonRuleNode(rules = listOf("a")),
        ).upsert("a", groupId = "g1")

        assertEquals(listOf("a"), layout!!.flattenRules())
        assertEquals("g1", layout.groupIdByRule { it }["a"])
        assertEquals(1, layout.size)
    }

    @Test
    fun anEmptyGroupIdMovesARuleBackOutOfItsGroup() {
        val layout = nodes(DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a"))).upsert("a", groupId = "")

        assertEquals(emptyMap(), layout!!.groupIdByRule { it })
        assertEquals(listOf("a"), layout.flattenRules())
        // The group survives losing its last rule; deleting rules is not deleting the group.
        assertEquals(listOf("g1"), layout.groups().map { it.id })
    }

    /** Groups are addressed by id, so an unknown one is an error rather than an invented group. */
    @Test
    fun anUnknownGroupIsRejectedRatherThanCreated() {
        assertNull(nodes(DaemonRuleNode(rules = listOf("a"))).upsert("b", groupId = "nope"))
    }

    @Test
    fun removingARuleLeavesItsGroupBehind() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a")),
            DaemonRuleNode(rules = listOf("b")),
        ).removeRule("a") { it }

        assertEquals(listOf("b"), layout.flattenRules())
        assertEquals(listOf("g1"), layout.groups().map { it.id })
    }

    @Test
    fun anEmptyGroupSurvivesUntilItIsRemovedItself() {
        val created = nodes(DaemonRuleNode(rules = listOf("a"))).upsertGroup(DaemonRuleGroup("g1", ""))
        assertEquals(listOf("g1"), created.groups().map { it.id })

        // Studio creates a group before naming it, so the rename must find the same group, not add one.
        val renamed = created.upsertGroup(DaemonRuleGroup("g1", "Checkout"))
        assertEquals(listOf(DaemonRuleGroup("g1", "Checkout")), renamed.groups())
        assertEquals(2, renamed.size)
    }

    @Test
    fun removingAGroupKeepsItsRulesInPlaceUnlessAskedOtherwise() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1"), listOf("a", "b")),
            DaemonRuleNode(rules = listOf("c")),
        )

        val kept = layout.removeGroup("g1", withRules = false)!!
        assertEquals(listOf("a", "b", "c"), kept.flattenRules())
        assertTrue(kept.groups().isEmpty())

        val dropped = layout.removeGroup("g1", withRules = true)!!
        assertEquals(listOf("c"), dropped.flattenRules())
    }

    @Test
    fun removingAnUnknownGroupReportsItRatherThanSucceedingSilently() {
        assertNull(nodes(DaemonRuleNode(rules = listOf("a"))).removeGroup("nope", withRules = false))
    }

    /** An off group gates its rules while each rule keeps its own state for when it comes back. */
    @Test
    fun aGroupSwitchGatesItsRulesWithoutRewritingThem() {
        assertTrue(effectiveEnabled(ruleEnabled = true, group = DaemonRuleGroup("g1")))
        assertTrue(!effectiveEnabled(ruleEnabled = true, group = DaemonRuleGroup("g1", enabled = false)))
        assertTrue(!effectiveEnabled(ruleEnabled = false, group = DaemonRuleGroup("g1")))
        assertTrue(effectiveEnabled(ruleEnabled = true, group = null))
    }

    @Test
    fun mappingRulesLeavesTheStructureAlone() {
        val layout = nodes(
            DaemonRuleNode(DaemonRuleGroup("g1", "Checkout", enabled = false), listOf("a")),
            DaemonRuleNode(rules = listOf("b")),
        ).mapRules { it.uppercase() }

        assertEquals(listOf("A", "B"), layout.flattenRules())
        assertEquals(DaemonRuleGroup("g1", "Checkout", enabled = false), layout.first().group)
    }
}
