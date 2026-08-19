package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostMapLocalRule
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
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
    fun mapLocalRulesSurviveADaemonRestart() = runBlocking {
        val directory = Files.createTempDirectory("wailo-daemon-fixtures")
        try {
            harness(directory, deleteDirectory = false).use { first ->
                val client = first.client()
                try {
                    assertTrue(client.awaitReady())
                    client.replaceMapLocalRules(
                        listOf(
                            HostMapLocalRule(
                                id = "login",
                                urlPattern = "https://example.com/login",
                                body = """{"ok":true}""".toByteArray(),
                            ),
                        ),
                        enabled = true,
                        layout = "R|login|1|aHR0cHM6Ly9leGFtcGxlLmNvbS9sb2dpbg==||200|",
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
                    assertEquals("R|login|1|aHR0cHM6Ly9leGFtcGxlLmNvbS9sb2dpbg==||200|", client.mapLocalLayout.value)
                } finally {
                    client.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
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
