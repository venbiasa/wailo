package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.DaemonRuleNode
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.RuleGroup
import com.venbiasa.wailo.shared.RuleNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Studio's seam with the daemon's layout (ADR-0081). Breakpoints are the pure case — Map Local and seeds
 * carry bodies through their stores — so the structural rules are pinned here for all three.
 */
class DaemonLayoutAdoptionTest {
    private val grouped: List<BreakpointNode> = listOf(
        GroupNode(
            RuleGroup("checkout", "Checkout", enabled = false),
            listOf(
                BreakpointRuleDef(id = "pay", urlPattern = "https://example.com/pay", method = "POST,PUT"),
                BreakpointRuleDef(id = "cart", urlPattern = "https://example.com/cart", enabled = false),
            ),
        ),
        RuleNode(BreakpointRuleDef(id = "loose", urlPattern = "https://example.com/*", onRequest = true)),
    )

    @Test
    fun aPanelSurvivesTheRoundTripThroughTheDaemonsShape() {
        assertEquals(grouped, grouped.toDaemonBreakpointNodes().toBreakpointNodes())
    }

    /**
     * A rule inside a switched-off group travels with its *own* state. The daemon folds the group's
     * switch in on the way to the host, so sending the resolved value would make turning the group back
     * on silently enable rules the user had left off.
     */
    @Test
    fun aGroupsSwitchTravelsOnTheGroupRatherThanItsRules() {
        val published = grouped.toDaemonBreakpointNodes()

        assertEquals(DaemonRuleGroup("checkout", "Checkout", enabled = false), published.first().group)
        assertEquals(listOf(true, false), published.first().rules.map { it.enabled })
    }

    @Test
    fun anEmptyDaemonIsSeededFromStudiosOwnPrefsRatherThanBlankingThem() {
        assertEquals(grouped, initialBreakpointNodes(stored = grouped, daemonNodes = emptyList()))
    }

    /** Once the daemon has a layout it wins outright, including a group Studio has never seen. */
    @Test
    fun aDaemonWithALayoutOverridesWhateverStudioHadStored() {
        val fromDaemon = listOf(
            DaemonRuleNode(
                DaemonRuleGroup("agent", "Agent rules"),
                listOf(HostBreakpointRule(id = "written", urlPattern = "https://example.com/written")),
            ),
        )

        val adopted = initialBreakpointNodes(stored = grouped, daemonNodes = fromDaemon)

        assertEquals(
            listOf(GroupNode(RuleGroup("agent", "Agent rules"), listOf(BreakpointRuleDef(id = "written", urlPattern = "https://example.com/written")))),
            adopted,
        )
    }

    /** An empty group is a real state — Studio creates one before it is named — so it must survive. */
    @Test
    fun anEmptyGroupIsNotDroppedOnTheWayBack() {
        val fromDaemon = listOf(DaemonRuleNode<HostBreakpointRule>(DaemonRuleGroup("blank", "")))

        assertEquals(
            listOf(GroupNode(RuleGroup("blank", ""), emptyList<BreakpointRuleDef>())),
            fromDaemon.toBreakpointNodes(),
        )
    }
}
