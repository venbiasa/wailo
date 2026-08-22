package com.venbiasa.wailo.daemon

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The takeover's failure mode is a machine with no route out, so these drive a stand-in for
 * `networksetup` rather than the real one — the accident being fixed is precisely a test suite that
 * reconfigures the machine running it (ADR-0078).
 */
class SystemProxyTakeoverTest {
    private val directory = Files.createTempDirectory("wailo-takeover")
    private val statePath = directory.resolve("system-proxy.json")

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun stoppingUndoesATakeoverThisProcessNeverPerformed() {
        // The state observed in the wild: every service pointed at Wailo, no record anywhere, and a
        // controller that never applied it. Restoring can only replay a snapshot, so before this the
        // machine kept routing through a listener that had just closed.
        val machine = FakeMachine("Wi-Fi", "Thunderbolt Bridge").pointedAtWailo(9090)
        val controller = SystemProxyController(statePath, machine, listens = { false })

        assertFalse(controller.active, "nothing in this process knows about the takeover")
        assertTrue(controller.releaseStranded(9090))

        assertFalse(machine.web("Wi-Fi").enabled)
        assertFalse(machine.secure("Thunderbolt Bridge").enabled)
    }

    @Test
    fun aWorkingTakeoverIsLeftToTheDaemonThatOwnsIt() {
        // Two daemons can share a Mac. Something is answering, so the settings are doing their job and
        // are not this process's to undo.
        val machine = FakeMachine("Wi-Fi").pointedAtWailo(9090)
        val controller = SystemProxyController(statePath, machine, listens = { true })

        assertFalse(controller.releaseStranded(9090))
        assertTrue(machine.web("Wi-Fi").enabled)
    }

    @Test
    fun someoneElsesLocalProxyIsNotSweptUp() {
        val machine = FakeMachine("Wi-Fi").pointedAt("Wi-Fi", "127.0.0.1", 8888)
        val controller = SystemProxyController(statePath, machine, listens = { false })

        assertFalse(controller.releaseStranded(9090), "only our own port is ours to turn off")
        assertEquals(8888, machine.web("Wi-Fi").port)
    }

    @Test
    fun applyingOverAnUnrecordedTakeoverDoesNotRecordWailoAsWhatCameBefore() {
        // The way the breakage used to become permanent: re-reading the machine here captured Wailo's
        // own address as the thing to restore, so every later restore faithfully put it back.
        val machine = FakeMachine("Wi-Fi").pointedAtWailo(9090)
        val controller = SystemProxyController(statePath, machine, listens = { false })

        val applied = controller.apply(9090)
        assertTrue(applied.active)
        assertNull(applied.chainedTo, "forwarding through our own listener is a loop, not a route out")

        controller.restore()

        assertFalse(machine.web("Wi-Fi").enabled, "a restore has to end with the machine off Wailo")
        assertFalse(machine.secure("Wi-Fi").enabled)
    }

    @Test
    fun anOutstandingRecordOutranksWhateverTheMachineSaysNow() {
        // Written before the first change, so it is the last description of the machine that predates
        // Wailo — unlike the machine itself, which Wailo has since overwritten.
        Files.writeString(
            statePath,
            """[{"service":"Wi-Fi","web":{"enabled":true,"server":"proxy.internal","port":8080},""" +
                """"secure":{"enabled":true,"server":"proxy.internal","port":8080}}]""",
        )
        val machine = FakeMachine("Wi-Fi").pointedAtWailo(9090)
        val controller = SystemProxyController(statePath, machine, listens = { false })

        val applied = controller.apply(9090)
        assertEquals("proxy.internal", applied.chainedTo?.host)

        controller.restore()

        assertEquals("proxy.internal", machine.web("Wi-Fi").server, "the real upstream comes back")
        assertTrue(machine.web("Wi-Fi").enabled)
    }

    @Test
    fun aMachineAlreadyBehindAProxyIsChainedThroughItAndGetsItBack() {
        val machine = FakeMachine("Wi-Fi").pointedAt("Wi-Fi", "proxy.internal", 8080)
        val controller = SystemProxyController(statePath, machine, listens = { false })

        val applied = controller.apply(9090)

        assertEquals("proxy.internal", applied.chainedTo?.host)
        assertEquals(8080, applied.chainedTo?.port)
        assertEquals("127.0.0.1", machine.web("Wi-Fi").server)

        controller.restore()

        assertEquals("proxy.internal", machine.web("Wi-Fi").server)
        assertFalse(Files.exists(statePath), "a completed restore leaves nothing to recover")
    }

    @Test
    fun theRecordSurvivesTheProcessThatWroteIt() {
        val machine = FakeMachine("Wi-Fi").pointedAt("Wi-Fi", "proxy.internal", 8080)
        SystemProxyController(statePath, machine, listens = { false }).apply(9090)

        // A different daemon, with no memory of the takeover, reading the same machine-scoped record.
        val successor = SystemProxyController(statePath, machine, listens = { false })
        assertTrue(successor.recover())

        assertEquals("proxy.internal", machine.web("Wi-Fi").server)
        assertTrue(machine.web("Wi-Fi").enabled)
    }
}

/**
 * A Mac's proxy settings as `networksetup` presents them: enough of the command surface to answer the
 * reads and record the writes.
 */
private class FakeMachine(private vararg val services: String) : NetworkSetup {
    private val settings = mutableMapOf<Pair<String, String>, ProxySetting>()

    fun web(service: String) = get(service, SystemProxyController.WEB)

    fun secure(service: String) = get(service, SystemProxyController.SECURE)

    fun pointedAt(service: String, server: String, port: Int) = apply {
        settings[service to SystemProxyController.WEB] = ProxySetting(true, server, port)
        settings[service to SystemProxyController.SECURE] = ProxySetting(true, server, port)
    }

    fun pointedAtWailo(port: Int) = apply {
        services.forEach { pointedAt(it, "127.0.0.1", port) }
    }

    private fun get(service: String, kind: String) =
        settings[service to kind] ?: ProxySetting(false, "", 0)

    override fun run(arguments: List<String>): String? {
        val command = arguments.first()
        val service = arguments.getOrNull(1)
        if (command == "-listallnetworkservices") {
            return (listOf("An asterisk (*) denotes that a network service is disabled.") + services)
                .joinToString("\n")
        }
        if (service !in services) return null
        return when {
            command.startsWith("-get") -> get(service!!, command.removePrefix("-get")).let {
                "Enabled: ${if (it.enabled) "Yes" else "No"}\nServer: ${it.server}\nPort: ${it.port}\n"
            }
            command.endsWith("state") -> {
                val kind = command.removePrefix("-set").removeSuffix("state")
                val current = get(service!!, kind)
                settings[service to kind] = ProxySetting(arguments[2] == "on", current.server, current.port)
                ""
            }
            command.startsWith("-set") -> {
                val kind = command.removePrefix("-set")
                val current = get(service!!, kind)
                settings[service to kind] = ProxySetting(current.enabled, arguments[2], arguments[3].toInt())
                ""
            }
            else -> null
        }
    }
}
