package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLocalLayoutTest {

    private fun rule(id: String, name: String = id, enabled: Boolean = true, url: String = "https://x/$id") =
        MapLocalRuleDef(id = id, name = name, enabled = enabled, urlPattern = url, inline = true)

    private fun group(id: String, name: String = id, enabled: Boolean = true) = MapLocalGroup(id, name, enabled)

    // A loose rule, a group with two rules, then another loose rule — the interleaved shape the UI allows.
    private fun sample(): List<MapLocalNode> = listOf(
        RuleNode(rule("r1")),
        GroupNode(group("g1"), listOf(rule("r2"), rule("r3"))),
        RuleNode(rule("r4")),
    )

    @Test
    fun allRulesIsInFlattenedTopToBottomOrder() {
        assertEquals(listOf("r1", "r2", "r3", "r4"), sample().allRules().map { it.id })
    }

    @Test
    fun rulesForMatchDropsDisabledGroupChildrenButKeepsPriorityOrder() {
        val nodes = listOf(
            RuleNode(rule("r1")),
            GroupNode(group("g1", enabled = false), listOf(rule("r2"), rule("r3"))),
            RuleNode(rule("r4", enabled = false)),
            GroupNode(group("g2"), listOf(rule("r5", enabled = false), rule("r6"))),
        )
        // g1 off -> r2,r3 out; r4 off -> out; g2 on -> r5 (off) out, r6 in. Order preserved.
        assertEquals(listOf("r1", "r6"), nodes.rulesForMatch().map { it.id })
    }

    @Test
    fun isRuleActiveRequiresGroupOnAndRuleOn() {
        val nodes = listOf(
            GroupNode(group("g1", enabled = false), listOf(rule("r2", enabled = true))),
            RuleNode(rule("r4", enabled = true)),
        )
        assertFalse(nodes.isRuleActive("r2")) // group off overrides the rule's own on
        assertTrue(nodes.isRuleActive("r4"))
        assertFalse(nodes.isRuleActive("missing"))
    }

    @Test
    fun upsertReplacesInPlaceAndAppendsNewAsLoose() {
        val nodes = sample()
        val edited = nodes.upsertRule(rule("r2", name = "renamed"))
        assertEquals("renamed", edited.findRule("r2")?.name)
        assertEquals(group("g1"), edited.groupNode("g1")?.group) // still in its group, order intact
        assertEquals(listOf("r2", "r3"), edited.groupNode("g1")!!.rules.map { it.id })

        val added = nodes.upsertRule(rule("new"))
        assertEquals("new", added.last().id) // appended at top level
        assertNull(added.groupOf("new")) // ...as a loose rule
        assertEquals(sample().allRules().size + 1, added.allRules().size)
    }

    @Test
    fun insertRuleAfterLandsBesideItsSourceAndInheritsThatSourcesGroup() {
        val inGroup = sample().insertRuleAfter("r2", rule("copy"))
        assertEquals(listOf("r2", "copy", "r3"), inGroup.groupNode("g1")!!.rules.map { it.id })

        // Beside a loose rule is the top level, never the group that happens to follow it.
        val loose = sample().insertRuleAfter("r1", rule("copy"))
        assertEquals(listOf("r1", "copy", "g1", "r4"), loose.map { it.id })
        assertNull(loose.groupOf("copy"))

        // Nothing to sit beside: appended rather than dropped.
        val orphaned = sample().insertRuleAfter("missing", rule("copy"))
        assertEquals("copy", orphaned.last().id)
        assertNull(orphaned.groupOf("copy"))
    }

    @Test
    fun duplicateNameIsPrefixedAndNeverNumbered() {
        assertEquals("Copy of Login", duplicateRuleName("Login"))
        // Repeat copies are allowed to collide rather than growing a counter, but a copy of a copy still
        // reads as one; a nameless rule borrows the label the list shows it under.
        assertEquals("Copy of Copy of Login", duplicateRuleName("Copy of Login"))
        assertEquals("Copy of Untitled", duplicateRuleName(""))
    }

    @Test
    fun removeRuleLeavesAnEmptyGroupBehind() {
        val nodes = sample().removeRule("r2").removeRule("r3")
        val g = nodes.groupNode("g1")!!
        assertTrue(g.rules.isEmpty())
        assertNull(nodes.findRule("r2"))
    }

    @Test
    fun removeGroupAlsoRemovesItsRules() {
        val nodes = sample().removeGroup("g1")
        assertEquals(listOf("r1", "r4"), nodes.allRules().map { it.id })
        assertTrue(nodes.none { it is GroupNode })
    }

    @Test
    fun toggleAndRenameAreLocalized() {
        val nodes = sample().setGroupEnabled("g1", false).setRuleEnabled("r1", false).renameGroup("g1", "API")
        assertFalse(nodes.groupNode("g1")!!.group.enabled)
        assertEquals("API", nodes.groupNode("g1")!!.group.name)
        assertFalse(nodes.findRule("r1")!!.enabled)
        assertTrue(nodes.groupNode("g1")!!.rules.all { it.enabled }) // children untouched (state retained)
    }

    @Test
    fun moveRuleIntoGroupThenBackOut() {
        // r1 (loose) into g1 at child index 1 (between r2 and r3).
        val intoGroup = sample().moveRule("r1", InGroupAt("g1", 1))
        val g = intoGroup.groupNode("g1")!!
        assertEquals(listOf("r2", "r1", "r3"), g.rules.map { it.id })
        assertNull(intoGroup.groupOf("r4")) // r4 still loose

        // Now pull r1 back out to the very top (top-level index 0).
        val backOut = intoGroup.moveRule("r1", TopLevelAt(0))
        assertEquals("r1", backOut.first().id)
        assertNull(backOut.groupOf("r1"))
        assertEquals(listOf("r2", "r3"), backOut.groupNode("g1")!!.rules.map { it.id })
    }

    @Test
    fun assignRuleToGroupFilesARuleAtTheEndAndPullsItBackOut() {
        val filed = sample().assignRuleToGroup("r1", "g1")
        assertEquals(listOf("r2", "r3", "r1"), filed.groupNode("g1")!!.rules.map { it.id })
        assertEquals(listOf("g1", "r4"), filed.map { it.id })

        val loosened = filed.assignRuleToGroup("r1", null)
        assertEquals(listOf("r2", "r3"), loosened.groupNode("g1")!!.rules.map { it.id })
        assertEquals("r1", loosened.last().id)
        assertNull(loosened.groupOf("r1"))
    }

    @Test
    fun assignRuleToGroupLeavesARuleWhereItAlreadyIs() {
        // Saving a grouped rule repeats its group, which must not shuffle it to the end of that group.
        assertEquals(sample(), sample().assignRuleToGroup("r2", "g1"))
        assertEquals(sample(), sample().assignRuleToGroup("r1", ""))
    }

    /** A group that isn't there can't take the rule, and taking it out of the layout would lose it. */
    @Test
    fun assignRuleToGroupIgnoresAnUnknownGroupOrRule() {
        assertEquals(sample(), sample().assignRuleToGroup("r1", "missing-group"))
        assertEquals(sample(), sample().assignRuleToGroup("missing-rule", "g1"))
    }

    @Test
    fun moveGroupReordersTopLevel() {
        // Move g1 to the front. Index space is the layout minus the dragged group: [r1, r4] -> insert at 0.
        val moved = sample().moveGroup("g1", 0)
        assertEquals(listOf("g1", "r1", "r4"), moved.map { it.id })
    }

    @Test
    fun codecRoundTripsInterleavedGroupsAndEmptyGroup() {
        val nodes: List<MapLocalNode> = listOf(
            RuleNode(rule("r1", name = "First", url = "https://a/*")),
            GroupNode(
                group("g1", name = "Auth", enabled = false),
                listOf(
                    rule("r2", name = "Login").copy(
                        method = "POST",
                        statusCode = 201,
                        delayMillis = 750,
                        headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    ),
                    rule("r3", name = "Me"),
                ),
            ),
            GroupNode(group("g2", name = "Empty"), emptyList()),
            RuleNode(rule("r4", name = "Last")),
        )
        assertEquals(nodes, MapLocalLayoutCodec.decode(MapLocalLayoutCodec.encode(nodes)))
    }

    @Test
    fun codecEmptyStringIsEmptyLayout() {
        assertEquals(emptyList(), MapLocalLayoutCodec.decode(""))
        assertEquals("", MapLocalLayoutCodec.encode(emptyList()))
    }

    @Test
    fun legacyPrefixlessLineDecodesAsLooseRule() {
        // A pre-groups line is exactly a rule's fields with no G/R/C prefix; simulate by stripping "R|".
        val ruleLine = MapLocalLayoutCodec.encode(listOf(RuleNode(rule("rL", name = "Legacy")))).removePrefix("R|")
        val decoded = MapLocalLayoutCodec.decode(ruleLine)
        assertEquals(1, decoded.size)
        assertEquals("rL", decoded.single().id)
        assertEquals("Legacy", decoded.findRule("rL")?.name)
    }

    @Test
    fun legacyPreNameLineMigratesToUntitled() {
        val current = MapLocalLayoutCodec.encode(listOf(RuleNode(rule("rN", name = "Dropped")))).removePrefix("R|")
        val eightFields = current.substringBeforeLast('|').substringBeforeLast('|')
        assertEquals("Untitled", MapLocalLayoutCodec.decode(eightFields).findRule("rN")?.name)
    }

    @Test
    fun legacyPreDelayLineDefaultsToNoDelay() {
        val current = MapLocalLayoutCodec.encode(
            listOf(RuleNode(rule("rD", name = "Existing").copy(delayMillis = 500))),
        ).removePrefix("R|")
        val preDelay = current.substringBeforeLast('|')

        val decoded = MapLocalLayoutCodec.decode(preDelay).findRule("rD")

        assertEquals("Existing", decoded?.name)
        assertEquals(0, decoded?.delayMillis)
    }
}
