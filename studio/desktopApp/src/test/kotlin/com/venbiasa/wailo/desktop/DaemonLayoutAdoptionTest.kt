package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.daemon.DaemonRuleGroup
import com.venbiasa.wailo.daemon.DaemonRuleNode
import com.venbiasa.wailo.daemon.mapRules
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
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
import kotlin.test.assertEquals

/**
 * Studio's seam with the daemon's layout: the panel holds no copy of it, so these conversions are the
 * whole of what survives an edit (ADR-0081, ADR-0085). Breakpoints are the pure structural case; Map
 * Local and seeds add a body *reference*, which is what a publish repeats back (ADR-0086).
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
     * A publish repeats the daemon's reference and sends no bytes (ADR-0086). That is what makes a
     * reorder or a toggle cost the rows alone — and it has to survive the trip, since a rule that
     * published a blank reference would have the daemon read its body back as empty.
     */
    @Test
    fun aRulesBodyReferenceTravelsWithItAndNoBytesDo() {
        val known = mapOf(
            "profile" to HostMapLocalRule(
                id = "profile",
                urlPattern = "https://example.com/profile",
                bodySize = 16,
                bodyHash = "cafe",
            ),
        )

        val published = mapLocal.toDaemonMapLocalNodes(known).single().rules.single()

        assertEquals(16, published.bodySize)
        assertEquals("cafe", published.bodyHash)
        assertEquals(0, published.bodyCopy().size)
        assertEquals(mapLocal, mapLocal.toDaemonMapLocalNodes(known).toMapLocalNodes())
    }

    /**
     * A rule the daemon has never seen publishes an empty reference rather than borrowing one. Anything
     * else would name a body it does not have, and the bytes for a rule this new are travelling in the
     * publish's own body map.
     */
    @Test
    fun aRuleTheDaemonHasNotSeenPublishesAnEmptyReference() {
        val published = mapLocal.toDaemonMapLocalNodes(emptyMap()).single().rules.single()

        assertEquals(0, published.bodySize)
        assertEquals("", published.bodyHash)
    }

    /**
     * Content-Length is the daemon's, recomputed from the bytes it sends. Adopting it back would let an
     * authored header re-appear in the editor and then be published as a length that can truncate.
     */
    @Test
    fun theDaemonsContentLengthIsNotAdoptedBackIntoTheEditor() {
        val published = mapLocal.toDaemonMapLocalNodes(emptyMap())
        val withLength = published.mapRules { rule ->
            HostMapLocalRule(
                id = rule.id,
                name = rule.name,
                enabled = rule.enabled,
                urlPattern = rule.urlPattern,
                methods = rule.methods,
                statusCode = rule.statusCode,
                headers = rule.headers + Header(name = "Content-Length", value_ = "4"),
                bodySize = 4,
            )
        }

        assertEquals(mapLocal, withLength.toMapLocalNodes())
    }

    private val seeds: List<SeedNode> = listOf(
        RuleNode(SeedRuleDef(id = "poll", urlPattern = "https://example.com/poll", method = "GET")),
    )

    @Test
    fun aSeedsBodyReferenceTravelsWithItAndNoBytesDo() {
        val known = mapOf("poll" to HostSeed(id = "poll", bodySize = 11, bodyHash = "f00d"))

        val published = seeds.toDaemonSeedNodes(known).single().rules.single()

        assertEquals(11, published.bodySize)
        assertEquals("f00d", published.bodyHash)
        assertEquals(0, published.bodyCopy().size)
        assertEquals(seeds, seeds.toDaemonSeedNodes(known).toSeedNodes())
    }
}
