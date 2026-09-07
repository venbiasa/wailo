package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.ProxyCertificate
import com.venbiasa.wailo.daemon.ProxyStatus
import com.venbiasa.wailo.daemon.provision.CaTrust
import com.venbiasa.wailo.daemon.provision.ProxyTarget
import com.venbiasa.wailo.daemon.provision.ProxyTargetKind
import com.venbiasa.wailo.daemon.provision.ProxyTargetOutcome
import com.venbiasa.wailo.daemon.provision.ProxyTargetScan
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ProxyMcpServiceTest {
    @Test
    fun readsProxyAndTargetStateAsStructuredTruth() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        try {
            val backend = ProxyBackend(host)
            val service = WailoMcpService(backend)

            val status = service.call("proxy_status", emptyMap())
            val targets = service.call("list_proxy_targets", emptyMap())
            val guide = service.call("get_proxy_setup_guide", emptyMap())
            val certificate = service.call("get_proxy_ca", emptyMap())

            assertFalse(status.isError)
            assertEquals("192.168.1.20:9090", status.data["reachable_address"])
            assertTrue((status.data["warnings"] as List<*>).isNotEmpty())
            assertFalse(targets.isError)
            assertEquals(true, targets.data["supported"])
            val target = (targets.data["targets"] as List<*>).single() as Map<*, *>
            assertEquals("android_device", target["kind"])
            assertEquals("Install the staged root on the phone.", target["action_required"])
            assertFalse(guide.isError)
            assertEquals("http://wailo.test/setup", guide.data["setup_url"])
            assertEquals(false, certificate.data["installed"])
        } finally {
            host.stop()
        }
    }

    @Test
    fun aPartialMutationIsAnErrorWithoutHidingWhatChanged() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        try {
            val service = WailoMcpService(ProxyBackend(host))

            val response = service.call(
                "setup_proxy_target",
                mapOf("id" to "partial", "confirm" to true),
            )

            assertTrue(response.isError)
            assertEquals(true, response.data["proxy_set"])
            assertEquals(true, response.data["cleanup_pending"])
            assertEquals("Could not save the recovery record.", response.data["error"])
        } finally {
            host.stop()
        }
    }

    @Test
    fun networkMutationsRequireExplicitArgumentsAndCarryDestructiveHints() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        try {
            val service = WailoMcpService(ProxyBackend(host))

            val unconfirmed: Map<String, Map<String, Any?>> = mapOf(
                "set_proxy" to mapOf("enabled" to true),
                "set_proxy_port" to mapOf("port" to 9091),
                "set_proxy_lan" to mapOf("enabled" to false),
                "set_system_proxy" to mapOf("enabled" to true),
                "set_proxy_decrypt_hosts" to mapOf("hosts" to listOf("api.example.com")),
                "ensure_proxy_ca" to emptyMap(),
                "rotate_proxy_ca" to emptyMap(),
                "remove_proxy_ca" to emptyMap(),
                "setup_proxy_target" to mapOf("id" to "phone-1"),
                "clear_proxy_target" to mapOf("id" to "phone-1"),
            )
            unconfirmed.forEach { (tool, arguments) ->
                val refused = service.call(tool, arguments)
                assertTrue(refused.isError, tool)
                assertTrue(refused.text.contains("confirm=true"), "$tool: ${refused.text}")
            }
            val changed = service.call(
                "set_proxy_lan",
                mapOf("enabled" to false, "confirm" to true),
            )

            assertFalse(changed.isError)
            assertEquals(false, changed.data["lan"])
            unconfirmed.keys.forEach { name ->
                val definition = WailoMcpTools.definitions.single { it.name == name }
                assertTrue(definition.destructive, name)
                assertTrue("confirm" in (definition.inputSchema["required"] as List<*>), name)
            }
            assertTrue(WailoMcpTools.definitions.single { it.name == "proxy_status" }.readOnly)
        } finally {
            host.stop()
        }
    }
}

private class ProxyBackend(host: HeadlessHost) : McpBackend by LocalMcpBackend(host, 8899) {
    private var status = ProxyStatus(
        running = true,
        port = 9090,
        lan = true,
        lanAddress = "192.168.1.20",
        systemProxySupported = true,
        caInstalled = true,
        caFingerprint = "AA:BB",
        decryptHosts = listOf("api.example.com"),
    )

    override val proxyStatus: ProxyStatus get() = status

    override suspend fun proxyTargets() = ProxyTargetScan(
        supported = true,
        targets = listOf(
            ProxyTarget(
                id = "phone-1",
                name = "Pixel",
                kind = ProxyTargetKind.ANDROID_DEVICE,
                proxySet = true,
                trust = CaTrust.NONE,
                actionRequired = "Install the staged root on the phone.",
            ),
        ),
    )

    override suspend fun setProxyLan(enabled: Boolean): ProxyStatus =
        status.copy(lan = enabled).also { status = it }

    override suspend fun currentProxyCertificate() =
        ProxyCertificate(installed = false)

    override suspend fun setUpProxyTarget(id: String): ProxyTargetOutcome =
        if (id == "partial") {
            ProxyTargetOutcome(
                proxySet = true,
                cleanupPending = true,
                error = "Could not save the recovery record.",
            )
        } else {
            ProxyTargetOutcome(
                target = proxyTargets().targets.single(),
                proxySet = true,
                trust = CaTrust.NONE,
                actionRequired = "Install the staged root on the phone.",
            )
        }

    override suspend fun ensureProxyCertificate() =
        ProxyCertificate(installed = true, sha256 = "AA:BB", pem = "certificate")
}
