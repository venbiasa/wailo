package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostSeed
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DaemonIntegrationTest {
    @Test
    fun clientsShareStateAndOneDisconnectDoesNotStopTheHost() = runBlocking {
        harness().use { harness ->
            val first = harness.client()
            val second = harness.client()
            try {
                assertTrue(first.awaitReady())
                assertTrue(second.awaitReady())
                first.upsertMapLocalRule(
                    HostMapLocalRule(
                        id = "shared",
                        urlPattern = "https://example.com/*",
                        body = "fixture".toByteArray(),
                    ),
                )

                withTimeout(5_000) {
                    while (second.mapLocalRules.value.none { it.id == "shared" }) delay(25)
                }
                first.close()
                second.setCapturing(false)

                assertFalse(second.capturing.value)
                assertEquals("shared", harness.host.mapLocalRules.value.single().id)
                assertFalse(harness.host.engine.capturing.value)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun concurrentClientsDoNotLoseRuleMutations() = runBlocking {
        harness().use { harness ->
            val clients = List(8) { harness.client() }
            try {
                clients.forEach { assertTrue(it.awaitReady()) }
                clients.mapIndexed { index, client ->
                    async {
                        client.upsertMapLocalRule(
                            HostMapLocalRule(
                                id = "rule-$index",
                                urlPattern = "https://example.com/$index",
                            ),
                        )
                    }
                }.awaitAll()

                assertEquals(8, harness.host.mapLocalRules.value.size)
            } finally {
                clients.forEach(DaemonClient::close)
            }
        }
    }

    @Test
    fun rejectsAClientWithAnotherProcessToken() = runBlocking {
        harness().use { harness ->
            val wrongToken = MemoryDaemonHandshakeStore().also {
                it.write(
                    DaemonHandshake(
                        controlPort = harness.server.port,
                        protocolVersion = DAEMON_CONTROL_PROTOCOL_VERSION,
                        token = ByteArray(TOKEN_BYTES) { 7 },
                    ),
                )
            }
            val client = DaemonRpcClient(wrongToken)

            assertFails { client.callRaw("ping") }
            Unit
        }
    }

    @Test
    fun reportsControlProtocolVersion() = runBlocking {
        harness().use { harness ->
            assertEquals(DAEMON_CONTROL_PROTOCOL_VERSION, harness.rpc().controlProtocolVersion())
        }
    }

    @Test
    fun publishesAnOsChosenControlPortClientsReachWithoutBeingToldIt() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(harness.server.port > 0)
                assertEquals(harness.server.port, client.controlPort)
                assertTrue(client.awaitReady())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun aClientThatCannotPollKeepsTheReasonItIsNotReady() = runBlocking {
        val harness = harness()
        val client = harness.client()
        try {
            assertTrue(client.awaitReady())
            assertNull(client.lastPollFailure)

            harness.close()

            withTimeout(5_000) {
                while (client.connected.value) delay(25)
            }
            // The poll loop has to swallow the failure to keep retrying; a frontend can only explain a
            // readiness timeout if the last one is still readable here.
            assertTrue(client.lastPollFailure?.message?.isNotBlank() == true)
        } finally {
            client.close()
        }
    }

    @Test
    fun mcpAccessAndRedactionSurviveTheClientThatSetThem() = runBlocking {
        harness().use { harness ->
            val first = harness.client()
            try {
                assertTrue(first.awaitReady())
                first.setMcpAccess(false)
                first.setMcpRedactSecrets(false)
            } finally {
                first.close()
            }

            val second = harness.client()
            try {
                assertTrue(second.awaitReady())
                withTimeout(5_000) {
                    while (second.mcpAccess.value || second.mcpRedactSecrets.value) delay(25)
                }
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun writesMcpChoicesThroughToTheSettingsFileTheDaemonReloads() {
        val directory = Files.createTempDirectory("wailo-daemon-settings")
        try {
            val settings = DaemonSettings(directory.resolve("settings.properties"))
            assertTrue(settings.load().mcpAccess)
            assertTrue(settings.load().mcpRedactSecrets)

            settings.update { it.copy(mcpAccess = false) }

            assertFalse(DaemonSettings(directory.resolve("settings.properties")).load().mcpAccess)
            assertTrue(DaemonSettings(directory.resolve("settings.properties")).load().mcpRedactSecrets)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun mapLocalRulesAndTheirGroupsSurviveADaemonRestart() = runBlocking {
        val directory = Files.createTempDirectory("wailo-daemon-fixtures")
        try {
            harness(directory, deleteDirectory = false).use { first ->
                val client = first.client()
                try {
                    assertTrue(client.awaitReady())
                    client.replaceMapLocalNodes(
                        listOf(
                            DaemonRuleNode(
                                group = DaemonRuleGroup("checkout", "Checkout"),
                                rules = listOf(
                                    HostMapLocalRule(
                                        id = "login",
                                        urlPattern = "https://example.com/login",
                                        body = """{"ok":true}""".toByteArray(),
                                    ),
                                ),
                            ),
                        ),
                        enabled = true,
                    )
                } finally {
                    client.close()
                }
            }
            harness(directory, deleteDirectory = false).use { second ->
                val client = second.client()
                try {
                    assertTrue(client.awaitReady())
                    withTimeout(5_000) {
                        while (client.mapLocalRules.value.none { it.id == "login" }) delay(25)
                    }
                    val rule = client.mapLocalRules.value.single()
                    assertEquals("https://example.com/login", rule.urlPattern)
                    assertEquals("""{"ok":true}""", rule.bodyCopy().decodeToString())
                    val node = client.mapLocalNodes.value.single()
                    assertEquals(DaemonRuleGroup("checkout", "Checkout"), node.group)
                    assertEquals("login", node.rules.single().id)
                } finally {
                    client.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * The point of moving grouping onto the daemon (ADR-0080): a rule an agent writes lands in a real
     * group, and every other frontend sees it there without Studio having been open.
     */
    @Test
    fun aRuleWrittenIntoAGroupIsReadBackInThatGroup() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(client.awaitReady())
                client.setRuleGroup(RULE_FAMILY_MAP_LOCAL, DaemonRuleGroup("checkout", "Checkout"))
                client.upsertMapLocalRule(
                    HostMapLocalRule(id = "login", urlPattern = "https://example.com/login"),
                    groupId = "checkout",
                )

                assertEquals(listOf(DaemonRuleGroup("checkout", "Checkout")), client.listRuleGroups(RULE_FAMILY_MAP_LOCAL))
                assertEquals(mapOf("login" to "checkout"), client.nodesFor(RULE_FAMILY_MAP_LOCAL).groupIdByRule { it })

                // A second frontend attaching later must be told the same thing.
                val observer = harness.client()
                try {
                    assertTrue(observer.awaitReady())
                    withTimeout(5_000) {
                        while (observer.mapLocalNodes.value.isEmpty()) delay(25)
                    }
                    val node = observer.mapLocalNodes.value.single()
                    assertEquals("checkout", node.group?.id)
                    assertEquals("login", node.rules.single().id)
                } finally {
                    observer.close()
                }
            } finally {
                client.close()
            }
        }
    }

    /**
     * A group's switch has to be resolved before the host sees the rule: what devices match on must be
     * right with no window open (invariant #2), so an off group cannot rely on a frontend to close it.
     */
    @Test
    fun aDisabledGroupPushesItsRulesToTheHostAsDisabled() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(client.awaitReady())
                client.setRuleGroup(RULE_FAMILY_MAP_LOCAL, DaemonRuleGroup("checkout", "Checkout"))
                client.upsertMapLocalRule(
                    HostMapLocalRule(id = "login", urlPattern = "https://example.com/login", enabled = true),
                    groupId = "checkout",
                )
                client.setRuleGroup(RULE_FAMILY_MAP_LOCAL, DaemonRuleGroup("checkout", "Checkout", enabled = false))

                withTimeout(5_000) {
                    while (harness.host.mapLocalRules.value.singleOrNull()?.enabled != false) delay(25)
                }
                // The rule keeps its own state, so switching the group back on restores it (ADR-0030).
                assertTrue(client.mapLocalNodes.value.single().rules.single().enabled)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun seedsSurviveADaemonRestartButTheArmedQueueDoesNot() = runBlocking {
        val directory = Files.createTempDirectory("wailo-daemon-seeds")
        try {
            harness(directory, deleteDirectory = false).use { first ->
                val client = first.client()
                try {
                    assertTrue(client.awaitReady())
                    client.replaceSeedNodes(
                        listOf(
                            DaemonRuleNode(
                                rules = listOf(
                                    HostSeed(
                                        id = "poll-1",
                                        urlPattern = "https://example.com/poll",
                                        body = """{"state":"pending"}""".toByteArray(),
                                    ),
                                ),
                            ),
                        ),
                        enabled = true,
                    )
                    assertEquals(1, client.fillSeeds())
                } finally {
                    client.close()
                }
            }
            harness(directory, deleteDirectory = false).use { second ->
                val client = second.client()
                try {
                    assertTrue(client.awaitReady())
                    withTimeout(5_000) {
                        while (client.seeds.value.none { it.id == "poll-1" }) delay(25)
                    }
                    val seed = client.seeds.value.single()
                    assertEquals("https://example.com/poll", seed.urlPattern)
                    assertEquals("""{"state":"pending"}""", seed.bodyCopy().decodeToString())
                    // A half-spent queue is a position in a run, not a preference (ADR-0041).
                    assertTrue(client.seedQueue.value.isEmpty())
                } finally {
                    client.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * The regression that made the master worth owning here: it used to be folded into the two list
     * flags before they were stored, so a restart could not tell a paused filter from an unarmed one and
     * a frontend guessed it back as off, every launch (ADR-0082).
     */
    @Test
    fun anOffCaptureFilterMasterSurvivesARestartWithItsListsStillArmed() = runBlocking {
        val directory = Files.createTempDirectory("wailo-daemon-filter")
        try {
            harness(directory, deleteDirectory = false).use { first ->
                val client = first.client()
                try {
                    assertTrue(client.awaitReady())
                    client.updateCaptureFilter(
                        allowlistEnabled = false,
                        allowPatterns = emptyList(),
                        blocklistEnabled = true,
                        blockPatterns = listOf("analytics.example.com"),
                        enabled = false,
                    )
                } finally {
                    client.close()
                }
            }
            harness(directory, deleteDirectory = false).use { second ->
                val client = second.client()
                try {
                    assertTrue(client.awaitReady())
                    withTimeout(5_000) {
                        while (client.captureFilter.value.block_patterns.isEmpty()) delay(25)
                    }
                    assertFalse(client.captureFilterEnabled.value)
                    assertTrue(client.captureFilter.value.blocklist_enabled)
                    // Devices still capture everything while the master is off.
                    assertFalse(second.host.engine.captureFilter.value.blocklist_enabled)
                } finally {
                    client.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Studio publishes the whole panel, so the master has to travel with the lists. Sent as two calls it
     * left a window where the daemon held this edit's lists beside the previous master, and the frontend
     * that adopts what the daemon reports handed the user back the master they had just changed.
     */
    @Test
    fun oneCallSetsTheListsAndTheMasterTogetherAndOmittingItLeavesTheMasterAlone() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(client.awaitReady())
                client.updateCaptureFilter(
                    allowlistEnabled = false,
                    allowPatterns = emptyList(),
                    blocklistEnabled = true,
                    blockPatterns = listOf("analytics.example.com"),
                    enabled = false,
                )
                assertFalse(harness.host.isCaptureFilterEnabled())
                assertTrue(harness.host.captureFilter.value.blocklist_enabled)
                assertFalse(harness.host.engine.captureFilter.value.blocklist_enabled)

                // A list-only edit — what the CLI and an agent send — must not disturb a paused master.
                client.updateCaptureFilter(false, emptyList(), true, listOf("metrics.example.com"))
                assertFalse(harness.host.isCaptureFilterEnabled())
                assertEquals(listOf("metrics.example.com"), harness.host.captureFilter.value.block_patterns)
                assertFalse(harness.host.engine.captureFilter.value.blocklist_enabled)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun everyFrontendSeesTheSameSeedLibraryAndArmedQueue() = runBlocking {
        harness().use { harness ->
            val author = harness.client()
            val observer = harness.client()
            try {
                assertTrue(author.awaitReady())
                assertTrue(observer.awaitReady())
                author.upsertSeed(
                    HostSeed(
                        id = "shared",
                        urlPattern = "https://example.com/*",
                        body = "canned".toByteArray(),
                    ),
                )
                withTimeout(5_000) {
                    while (observer.seeds.value.none { it.id == "shared" }) delay(25)
                }
                assertEquals("canned", observer.seeds.value.single().bodyCopy().decodeToString())
                // Nothing is armed until an explicit fill, so the observer can tell "authored" from "in play".
                assertTrue(observer.seedQueue.value.isEmpty())

                assertEquals(1, author.fillSeeds())
                withTimeout(5_000) {
                    while (observer.seedQueue.value.none { it.id == "shared" }) delay(25)
                }

                observer.setSeedsEnabled(false)
                withTimeout(5_000) {
                    while (author.seedsEnabled.value) delay(25)
                }
                assertFalse(harness.host.areSeedsEnabled())
            } finally {
                author.close()
                observer.close()
            }
        }
    }

    @Test
    fun aFrontendRefersToTheDaemonForExactlyAsLongAsItIsOpen() = runBlocking {
        harness().use { harness ->
            val client = harness.presentClient()
            try {
                assertTrue(client.awaitReady())
                withTimeout(5_000) {
                    while (harness.server.references == 0) delay(25)
                }
            } finally {
                client.close()
            }

            withTimeout(5_000) {
                while (harness.server.references > 0) delay(25)
            }
            assertEquals(0, harness.server.references)
        }
    }

    @Test
    fun aOneShotClientNeverRefersToTheDaemon() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(client.awaitReady())
                client.setCapturing(false)

                assertEquals(0, harness.server.references)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun stopsItselfOnceTheLastFrontendCloses() = runBlocking {
        harness().use { harness ->
            val idle = CountDownLatch(1)
            val watchdog = DaemonIdleWatchdog(
                lingerMillis = 1_000,
                referenced = { harness.server.references > 0 },
                lastActivityAtMillis = { harness.server.lastActivityAtMillis },
                onIdle = { idle.countDown() },
                checkIntervalMillis = 25,
            ).also(DaemonIdleWatchdog::start)
            try {
                val client = harness.presentClient()
                assertTrue(client.awaitReady())
                assertFalse(idle.await(500, TimeUnit.MILLISECONDS))

                client.close()

                assertTrue(idle.await(5, TimeUnit.SECONDS))
            } finally {
                watchdog.close()
            }
        }
    }

    @Test
    fun tellsAStudioApartFromAnyOtherFrontendHoldingAReference() = runBlocking {
        harness().use { harness ->
            val tool = harness.presentClient()
            try {
                assertTrue(tool.awaitReady())
                withTimeout(5_000) {
                    while (harness.server.references == 0) delay(25)
                }
                // A reference is a reference, but only a Studio is something Show Studio can raise.
                assertFalse(harness.server.studioAttached)

                val studio = harness.presentClient(CLIENT_KIND_STUDIO)
                try {
                    assertTrue(studio.awaitReady())
                    withTimeout(5_000) {
                        while (!harness.server.studioAttached) delay(25)
                    }
                } finally {
                    studio.close()
                }

                withTimeout(5_000) {
                    while (harness.server.studioAttached) delay(25)
                }
                assertFalse(harness.server.studioAttached)
            } finally {
                tool.close()
            }
        }
    }

    @Test
    fun relaysTheMenuBarAsksToFrontendsThatNeverMetTheAgent() = runBlocking {
        harness().use { harness ->
            val agent = harness.client()
            val studio = harness.presentClient(CLIENT_KIND_STUDIO)
            try {
                assertTrue(agent.awaitReady())
                assertTrue(studio.awaitReady())
                val show = studio.showStudioRequests.value
                val quit = studio.quitRequests.value

                agent.requestShowStudio()
                withTimeout(5_000) {
                    while (studio.showStudioRequests.value == show) delay(25)
                }
                agent.requestQuit()
                withTimeout(5_000) {
                    while (studio.quitRequests.value == quit) delay(25)
                }

                // Counters, not events: what a frontend reacts to is the increase, so an ask cannot be
                // dropped between two polls.
                assertEquals(1, studio.showStudioRequests.value)
                assertEquals(1, studio.quitRequests.value)
            } finally {
                agent.close()
                studio.close()
            }
        }
    }

    @Test
    fun unlockingAHostPersistsButTheWildcardEscapeHatchDoesNot() = runBlocking {
        val directory = Files.createTempDirectory("wailo-decrypt-hosts")
        val settings = DaemonSettings(directory.resolve("settings.properties"))
        try {
            harness(directory, deleteDirectory = false).use { harness ->
                val client = harness.client()
                try {
                    assertTrue(client.awaitReady())

                    assertEquals(listOf("api.example.com"), client.setProxyDecryptHosts(listOf("api.example.com")).decryptHosts)
                    assertEquals(listOf("api.example.com"), settings.load().proxyDecryptHosts)

                    // `*` reads back for this session, so the user sees what they turned on, but it is
                    // never written: an escape hatch that survived a restart would stop being one.
                    assertEquals(listOf("*"), client.setProxyDecryptHosts(listOf("*")).decryptHosts)
                    assertEquals(emptyList(), settings.load().proxyDecryptHosts)

                    // Replace, never merge, so revoking is the same call as granting.
                    assertEquals(emptyList(), client.setProxyDecryptHosts(emptyList()).decryptHosts)
                } finally {
                    client.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aFreshInstallBindsTheProxyWideAndAnExplicitNarrowingSurvives() {
        val directory = Files.createTempDirectory("wailo-proxy-bind")
        try {
            val file = directory.resolve("settings.properties")
            val settings = DaemonSettings(file)

            // ADR-0077 reversed ADR-0074's default: the clients a proxy exists for are mostly not on
            // this machine. The gate that remains is that nothing here starts a listener.
            assertTrue(settings.load().proxyLan, "a fresh install must be reachable by a device")

            settings.update { it.copy(proxyLan = false) }

            assertFalse(
                DaemonSettings(file).load().proxyLan,
                "narrowing has to outlive the daemon, or it is a choice the user re-makes every session",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun theRootIsExportedAsACertificateAndNeverAsAKey() = runBlocking {
        harness().use { harness ->
            val client = harness.client()
            try {
                assertTrue(client.awaitReady())
                // Nothing has asked for a root, so nothing should have minted one (ADR-0073).
                assertFalse(client.proxy.value.caInstalled)

                val certificate = client.proxyCertificate()
                assertTrue(certificate.installed, certificate.error)
                assertTrue(certificate.pem.startsWith("-----BEGIN CERTIFICATE-----"))
                assertFalse(certificate.pem.contains("PRIVATE KEY"))

                val rotated = client.rotateProxyCertificate()
                assertTrue(rotated.installed)
                assertNotEquals(certificate.sha256, rotated.sha256)

                assertFalse(client.removeProxyCertificate().installed)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun readsTheMenuBarAgentAsGoneAsSoonAsItsLockIsFree() {
        val directory = Files.createTempDirectory("wailo-menubar-lock")
        try {
            val lockPath = directory.resolve("menubar.lock")
            assertFalse(DaemonLauncher.lockHeld(lockPath))

            val held = DaemonSingleInstanceLock.tryAcquire(lockPath)

            // Studio's close button leans on this: with no agent there is no item to reopen from, so
            // hiding the last window would strand the app.
            assertTrue(DaemonLauncher.lockHeld(lockPath))
            held?.close()
            assertFalse(DaemonLauncher.lockHeld(lockPath))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun harness(
        directory: java.nio.file.Path = Files.createTempDirectory("wailo-daemon-test"),
        deleteDirectory: Boolean = true,
    ): Harness {
        val handshakeStore = MemoryDaemonHandshakeStore()
        val host = HeadlessHost.wrap(WailoEngine(port = ServerSocket(0).use { it.localPort }))
        val runtime = DaemonRuntime(
            host = host,
            usb = NoopUsbController(8900),
            adb = NoopAdbController(),
            settings = DaemonSettings(directory.resolve("settings.properties")),
            pairingSupported = false,
            fixtures = DaemonFixturesStore(directory),
        )
        val server = DaemonServer(
            runtime = runtime,
            handshakeStore = handshakeStore,
            onStop = {},
        ).also(DaemonServer::start)
        return Harness(host, runtime, server, handshakeStore, directory, deleteDirectory)
    }

    private class Harness(
        val host: HeadlessHost,
        private val runtime: DaemonRuntime,
        val server: DaemonServer,
        private val handshakeStore: MemoryDaemonHandshakeStore,
        private val directory: java.nio.file.Path,
        private val deleteDirectory: Boolean = true,
    ) : AutoCloseable {
        fun client() = DaemonClient(
            rpc = DaemonRpcClient(handshakeStore),
            autoRestart = false,
        )

        fun presentClient(kind: String = CLIENT_KIND_UNKNOWN) = DaemonClient(
            rpc = DaemonRpcClient(handshakeStore),
            autoRestart = false,
            holdsPresence = true,
            clientKind = kind,
        )

        fun rpc() = DaemonRpcClient(handshakeStore)

        override fun close() {
            server.close()
            runtime.close()
            if (deleteDirectory) directory.toFile().deleteRecursively()
        }
    }
}
