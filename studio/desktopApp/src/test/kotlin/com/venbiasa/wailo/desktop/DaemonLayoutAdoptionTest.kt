package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.DaemonRuleNode
import com.venbiasa.wailo.daemon.mapRules
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.BreakpointNode
import com.venbiasa.wailo.shared.BreakpointRuleDef
import com.venbiasa.wailo.shared.GroupNode
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.ResponseHeader
import com.venbiasa.wailo.shared.RuleGroup
import com.venbiasa.wailo.shared.RuleNode
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Studio's seam with the daemon's layout: the panel holds no copy of it, so these conversions are the
 * whole of what survives an edit (ADR-0081, ADR-0085). Breakpoints are the pure structural case; Map
 * Local and seeds add the bodies, which now travel with the rule instead of sitting in a local file.
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

    /** An empty group is a real state — Studio creates one before it is named — so it must survive. */
    @Test
    fun anEmptyGroupIsNotDroppedOnTheWayBack() {
        val fromDaemon = listOf(DaemonRuleNode<HostBreakpointRule>(DaemonRuleGroup("blank", "")))

        assertEquals(
            listOf(GroupNode(RuleGroup("blank", ""), emptyList<BreakpointRuleDef>())),
            fromDaemon.toBreakpointNodes(),
        )
    }

    private val mapLocal: List<MapLocalNode> = listOf(
        GroupNode(
            RuleGroup("fixtures", "Fixtures"),
            listOf(
                MapLocalRuleDef(
                    id = "profile",
                    name = "Profile 500",
                    urlPattern = "https://example.com/profile",
                    method = "GET",
                    statusCode = 500,
                    headers = listOf(ResponseHeader("Content-Type", "application/json")),
                    inline = true,
                ),
            ),
        ),
    )

    /**
     * The body is what a Map Local rule is *for*, so it has to make the trip with the rule — there is no
     * longer a local file the panel could fall back to if it did not.
     */
    @Test
    fun aRulesBodyTravelsWithItAndComesBack() {
        val authored = """{"error":"nope"}""".toByteArray()

        val published = mapLocal.toDaemonMapLocalNodes { authored }

        assertContentEquals(authored, published.single().rules.single().bodyCopy())
        assertEquals(mapLocal, published.toMapLocalNodes())
    }

    /**
     * Content-Length is the daemon's, recomputed from the bytes it sends. Adopting it back would let an
     * authored header re-appear in the editor and then be published as a length that can truncate.
     */
    @Test
    fun theDaemonsContentLengthIsNotAdoptedBackIntoTheEditor() {
        val published = mapLocal.toDaemonMapLocalNodes { ByteArray(4) }
        val withLength = published.mapRules { rule ->
            HostMapLocalRule(
                id = rule.id,
                name = rule.name,
                enabled = rule.enabled,
                urlPattern = rule.urlPattern,
                methods = rule.methods,
                statusCode = rule.statusCode,
                headers = rule.headers + Header(name = "Content-Length", value_ = "4"),
                body = rule.bodyCopy(),
            )
        }

        assertEquals(mapLocal, withLength.toMapLocalNodes())
    }

    private val seeds: List<SeedNode> = listOf(
        RuleNode(SeedRuleDef(id = "poll", urlPattern = "https://example.com/poll", method = "GET")),
    )

    @Test
    fun aSeedsBodyTravelsWithItAndComesBack() {
        val authored = """{"ok":true}""".toByteArray()

        val published = seeds.toDaemonSeedNodes { authored }

        assertContentEquals(authored, published.single().rules.single().bodyCopy())
        assertEquals(seeds, published.toSeedNodes())
    }
}
