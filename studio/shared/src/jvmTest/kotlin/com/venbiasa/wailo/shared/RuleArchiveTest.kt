package com.venbiasa.wailo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleArchiveTest {

    private fun rule(id: String, name: String = id, enabled: Boolean = true) = MapLocalRuleDef(
        id = id,
        name = name,
        enabled = enabled,
        urlPattern = "https://api.example.com/$id",
        method = "GET",
        statusCode = 201,
        delayMillis = 650,
        headers = listOf(ResponseHeader("Content-Type", "application/json")),
        inline = true,
    )

    private fun sample(): List<MapLocalNode> = listOf(
        RuleNode(rule("r1")),
        GroupNode(MapLocalGroup("g1", "Checkout", enabled = false), listOf(rule("r2"), rule("r3"))),
        RuleNode(rule("r4", enabled = false)),
    )

    private fun archiveOf(nodes: List<MapLocalNode>) =
        RuleArchive(mapLocal = ArchivedSection(enabled = true, nodes = nodes.toArchivedNodes { it.toArchived("") }))

    private fun RuleArchive.mapLocalLayout(): List<MapLocalNode> =
        mapLocal!!.nodes.toLayoutNodes { it.toRuleDef(filePathResolves = false) }

    @Test
    fun roundTripsGroupsNamesOrderAndRuleFields() {
        val decoded = RuleArchiveCodec.decode(RuleArchiveCodec.encode(archiveOf(sample())))
        assertNotNull(decoded)
        val nodes = decoded.mapLocalLayout()

        // The interleaved top-level shape is the match priority, so it has to survive verbatim.
        assertEquals(listOf("r1", "g1", "r4"), nodes.map { it.id })
        assertEquals(listOf("r1", "r2", "r3", "r4"), nodes.allRules().map { it.id })

        val group = nodes.groupNode("g1")
        assertNotNull(group)
        assertEquals("Checkout", group.group.name)
        assertFalse(group.group.enabled)

        val restored = nodes.findRule("r1")
        assertNotNull(restored)
        assertEquals("https://api.example.com/r1", restored.urlPattern)
        assertEquals("GET", restored.method)
        assertEquals(201, restored.statusCode)
        assertEquals(650, restored.delayMillis)
        assertEquals(listOf(ResponseHeader("Content-Type", "application/json")), restored.headers)
        assertFalse(nodes.findRule("r4")!!.enabled)
    }

    @Test
    fun bodyEntryNamesSurviveSoTheBytesCanBeFoundAgain() {
        // The manifest carries only the name; losing it would strand a body that is still in the
        // container, and the rule would import as a mock that serves nothing.
        val archive = RuleArchive(
            mapLocal = ArchivedSection(
                nodes = listOf(RuleNode(rule("r1"))).toArchivedNodes { it.toArchived("bodies/map-local/r1.json") },
            ),
        )
        val decoded = RuleArchiveCodec.decode(RuleArchiveCodec.encode(archive))!!
        assertEquals("bodies/map-local/r1.json", decoded.mapLocal!!.nodes.first().rules.first().bodyEntry)
    }

    @Test
    fun decodeIgnoresKeysAnOlderBuildDoesNotKnow() {
        val forwardCompatible = """
            {
              "formatVersion": 99,
              "exportedAt": "2026-08-23T00:00:00Z",
              "somethingFromTheFuture": { "nested": true },
              "mapLocal": { "enabled": true, "nodes": [ { "group": null, "rules": [ { "id": "r1", "surprise": 1 } ] } ] }
            }
        """.trimIndent()
        val decoded = RuleArchiveCodec.decode(forwardCompatible)
        assertNotNull(decoded)
        assertEquals(listOf("r1"), decoded.mapLocalLayout().allRules().map { it.id })
        assertEquals(0, decoded.mapLocalLayout().findRule("r1")?.delayMillis)
    }

    @Test
    fun decodeReturnsNullForAFileThatIsNotAnArchive() {
        assertNull(RuleArchiveCodec.decode("not json at all"))
        assertNull(RuleArchiveCodec.decode(""))
    }

    @Test
    fun mergeAppendsUnknownRulesAndLeavesExistingOnesUntouched() {
        val current = listOf<MapLocalNode>(RuleNode(rule("r1", name = "mine")))
        val incoming = listOf<MapLocalNode>(
            RuleNode(rule("r1", name = "theirs")),
            RuleNode(rule("r9", name = "new")),
        )

        val merged = current.mergeIn(incoming)

        assertEquals(listOf("r9"), merged.addedRuleIds)
        // The colliding id keeps *this* machine's version — an import never reverts an edit.
        assertEquals("mine", merged.nodes.findRule("r1")!!.name)
        // New rules land last, which is the lowest match priority, so they cannot shadow r1.
        assertEquals(listOf("r1", "r9"), merged.nodes.allRules().map { it.id })
    }

    @Test
    fun mergingTheSameArchiveTwiceChangesNothing() {
        val incoming = sample()
        val once = emptyList<MapLocalNode>().mergeIn(incoming)
        val twice = once.nodes.mergeIn(incoming)

        assertEquals(emptyList(), twice.addedRuleIds)
        assertEquals(emptyList(), twice.addedGroupIds)
        assertEquals(once.nodes, twice.nodes)
    }

    @Test
    fun mergeFoldsNewRulesIntoAGroupThatAlreadyExists() {
        val current = listOf<MapLocalNode>(GroupNode(MapLocalGroup("g1", "Checkout"), listOf(rule("r2"))))
        val incoming = listOf<MapLocalNode>(
            GroupNode(MapLocalGroup("g1", "Renamed elsewhere"), listOf(rule("r2"), rule("r7"))),
        )

        val merged = current.mergeIn(incoming)

        assertEquals(listOf("r7"), merged.addedRuleIds)
        assertEquals(emptyList(), merged.addedGroupIds)
        val group = merged.nodes.groupNode("g1")!!
        // The group itself is existing state, so its name is not overwritten either.
        assertEquals("Checkout", group.group.name)
        assertEquals(listOf("r2", "r7"), group.rules.map { it.id })
        assertEquals(1, merged.nodes.size)
    }

    @Test
    fun mergeSkipsAnIncomingGroupWhoseRulesAreAllAlreadyPresent() {
        val current = listOf<MapLocalNode>(RuleNode(rule("r2")))
        val incoming = listOf<MapLocalNode>(GroupNode(MapLocalGroup("g1", "Checkout"), listOf(rule("r2"))))

        val merged = current.mergeIn(incoming)

        // Adding the group empty would litter the panel with a container holding nothing.
        assertEquals(emptyList(), merged.addedGroupIds)
        assertEquals(listOf("r2"), merged.nodes.allRules().map { it.id })
        assertEquals(1, merged.nodes.size)
    }

    @Test
    fun mergeKeepsAnIncomingGroupThatWasEmptyInTheArchive() {
        val merged = emptyList<MapLocalNode>().mergeIn(listOf(GroupNode(MapLocalGroup("g1", "Empty"), emptyList())))

        assertEquals(listOf("g1"), merged.addedGroupIds)
        assertNotNull(merged.nodes.groupNode("g1"))
    }

    @Test
    fun aFileBackedRuleFallsBackToTheArchivedCopyWhenThePathIsGone() {
        val fileRule = rule("r1").copy(inline = false, filePath = "/somewhere/only/the/author/has.json")
        val archived = listOf<MapLocalNode>(RuleNode(fileRule))
            .toArchivedNodes { it.toArchived("bodies/map-local/r1.json") }

        val onAnotherMachine = archived.toLayoutNodes { it.toRuleDef(filePathResolves = false) }
        val restored = onAnotherMachine.findRule("r1")!!
        assertTrue(restored.inline)
        assertEquals("", restored.filePath)

        val backHome = archived.toLayoutNodes { it.toRuleDef(filePathResolves = true) }
        val kept = backHome.findRule("r1")!!
        assertFalse(kept.inline)
        assertEquals("/somewhere/only/the/author/has.json", kept.filePath)
    }

    @Test
    fun captureFilterMergeUnionsHostsAndLeavesTheSwitchesAlone() {
        val current = CaptureFilterState(
            masterEnabled = true,
            allowEnabled = true,
            allowHosts = listOf("a.example.com"),
            blockEnabled = false,
            blockHosts = emptyList(),
        )
        val incoming = ArchivedCaptureFilter(
            masterEnabled = false,
            allowEnabled = false,
            allowHosts = listOf("a.example.com", "b.example.com"),
            blockEnabled = true,
            blockHosts = listOf("ads.example.com"),
        )

        val merged = current.mergeIn(incoming)

        assertEquals(listOf("a.example.com", "b.example.com"), merged.allowHosts)
        assertEquals(listOf("ads.example.com"), merged.blockHosts)
        // An import contributes hosts; whether this capture is filtered at all stays the user's call.
        assertTrue(merged.masterEnabled)
        assertTrue(merged.allowEnabled)
        assertFalse(merged.blockEnabled)
    }

    @Test
    fun breakpointAndSeedSectionsRoundTripThroughTheSameSchema() {
        val breakpoints = listOf<BreakpointNode>(
            RuleNode(BreakpointRuleDef(id = "bp1", urlPattern = "https://x/*", onRequest = true, onResponse = false)),
        )
        val seeds = listOf<SeedNode>(
            RuleNode(SeedRuleDef(id = "s1", urlPattern = "https://y/*", statusCode = 503, delayMillis = 900)),
        )
        val archive = RuleArchive(
            breakpoints = ArchivedSection(nodes = breakpoints.toArchivedNodes { it.toArchived() }),
            seeds = ArchivedSection(nodes = seeds.toArchivedNodes { it.toArchived("") }),
        )

        val decoded = RuleArchiveCodec.decode(RuleArchiveCodec.encode(archive))!!
        // A section the file omits must read as "says nothing", not as "is empty" — the import leaves
        // that tool alone rather than deciding it should have no rules.
        assertNull(decoded.mapLocal)

        val bp = decoded.breakpoints!!.nodes.toLayoutNodes { it.toRuleDef() }.findRule("bp1")!!
        assertTrue(bp.onRequest)
        assertFalse(bp.onResponse)
        val seed = decoded.seeds!!.nodes.toLayoutNodes { it.toRuleDef() }.findRule("s1")!!
        assertEquals(503, seed.statusCode)
        assertEquals(900, seed.delayMillis)
    }
}
