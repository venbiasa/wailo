package com.venbiasa.wailo.daemon.provision

import com.venbiasa.wailo.daemon.EphemeralCertificateAuthorityStore
import com.venbiasa.wailo.daemon.WailoCertificateAuthority
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provisioning drives a developer's real simulators and emulators, so every case here runs against
 * stand-in tools: the outcome is the same on any host and the suite can never reconfigure the emulator of
 * whoever is running it (ADR-0083/0090).
 */
class ProxyTargetProvisionerTest {
    private val directory = Files.createTempDirectory("wailo-provision")
    private val statePath = directory.resolve("proxy-targets.json")
    private val pem = WailoCertificateAuthority(EphemeralCertificateAuthorityStore()).ensure()!!.pem

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun provisioner(adb: DeviceTool? = null, simctl: DeviceTool? = null) =
        ProxyTargetProvisioner.over(adb = adb, simctl = simctl, statePath = statePath)

    @Test
    fun withNoToolchainThereIsNothingToOfferAndNothingIsRecorded() {
        val provisioner = provisioner()

        assertFalse(provisioner.supported)
        assertTrue(provisioner.targets().isEmpty())
        assertNotNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        assertFalse(Files.exists(statePath), "nothing was changed, so there is nothing to undo")
    }

    @Test
    fun anUnreadableRecoveryRecordBlocksMutationAndIsReported() {
        Files.writeString(statePath, "{not-json")
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = provisioner(adb = adb)

        assertTrue(provisioner.targets().isNotEmpty())
        assertNotNull(provisioner.lastError)
        adb.commands.clear()

        assertNotNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        assertTrue(adb.commands.none { it.contains("settings") })
    }

    @Test
    fun aRecordThatCannotBeWrittenPreventsTheFirstDeviceMutation() {
        val blockedParent = directory.resolve("not-a-directory")
        Files.writeString(blockedParent, "occupied")
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = ProxyTargetProvisioner.over(
            adb = adb,
            simctl = null,
            statePath = blockedParent.resolve("proxy-targets.json"),
        )

        assertNotNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        assertTrue(
            adb.commands.none { command ->
                command.contains("push") ||
                    command.contains("root") ||
                    command.contains("remount") ||
                    command.contains("settings") && (
                        command.contains("put") ||
                            command.contains("delete")
                    )
            },
            "commands=${adb.commands}",
        )
    }

    @Test
    fun readyEmulatorsAndUsbDebuggingPhonesAreOffered() {
        val adb = FakeAdb(
            devices = """
                List of devices attached
                emulator-5554          device model:sdk_gphone64_arm64
                emulator-5556          offline
                0A1B2C3D               device model:Pixel_8
                """.trimIndent(),
        )

        val targets = provisioner(adb = adb).targets()

        assertEquals(listOf("emulator-5554", "0A1B2C3D"), targets.map { it.id })
        assertEquals(ProxyTargetKind.ANDROID_EMULATOR, targets.first().kind)
        assertEquals(ProxyTargetKind.ANDROID_DEVICE, targets.last().kind)
        assertEquals("API 34", targets.first().detail)
        assertTrue(targets.last().detail.contains("ADB connected"))
    }

    @Test
    fun aWritableSystemEmulatorGetsTheRootWhereEveryAppTrustsIt() {
        val adb = FakeAdb(devices = ONE_EMULATOR)

        val outcome = provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        assertNull(outcome.error)
        assertTrue(outcome.proxySet)
        assertEquals(CaTrust.SYSTEM, outcome.trust)
        // 10.0.2.2, not the machine's LAN address: the emulator reaches the host's loopback there, so this
        // path never needs the wide bind (ADR-0077).
        assertTrue(
            adb.commands.any { it.containsAll(listOf("settings", "put", "global", "http_proxy", "10.0.2.2:9090")) },
            "the emulator has to be pointed at the host loopback alias",
        )
        assertTrue(adb.commands.any { it.containsAll(listOf("chown", "0:0")) })
        assertTrue(adb.commands.any { it.contains("restorecon") })
    }

    @Test
    fun releaseRestoresTheProxyThatWasThereBeforeWailo() {
        val adb = FakeAdb(devices = ONE_EMULATOR, initialProxy = "corporate-proxy.example:8080")
        val provisioner = provisioner(adb = adb)

        assertNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        assertEquals("10.0.2.2:9090", adb.proxy)

        assertNull(provisioner.clear("emulator-5554").error)
        assertEquals("corporate-proxy.example:8080", adb.proxy)
    }

    @Test
    fun clearAllRetriesCertificateCleanupAfterTheProxyWasRestored() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = provisioner(adb = adb)
        assertNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        adb.rootable = false

        val partial = provisioner.clear("emulator-5554")

        assertNotNull(partial.error)
        assertFalse(partial.proxySet)
        assertTrue(partial.cleanupPending)
        adb.rootable = true
        assertTrue(provisioner.clearAll().all { it.error == null })
        assertFalse(Files.exists(statePath))
    }

    @Test
    fun aUsbDebuggingPhoneUsesTheReachableLanAddress() {
        val adb = FakeAdb(devices = ONE_PHONE, rootable = false)
        val provisioner = provisioner(adb = adb)

        assertNotNull(provisioner.setUp("0A1B2C3D", pem, 9090).error)
        val outcome = provisioner.setUp("0A1B2C3D", pem, 9090, "192.168.1.20")

        assertNull(outcome.error)
        assertTrue(outcome.proxySet)
        assertEquals("192.168.1.20:9090", adb.proxy)
        assertEquals(CaTrust.NONE, outcome.trust)
        assertTrue(outcome.actionRequired.contains("On the device"))
    }

    @Test
    fun anEmulatorIsRecognisedAfterItsAdbSerialChanges() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = provisioner(adb = adb)
        assertNull(provisioner.setUp("emulator-5554", pem, 9090).error)

        adb.devices = "List of devices attached\nemulator-5570\tdevice model:sdk_gphone64_arm64"

        val target = provisioner.targets().single()
        assertEquals("emulator-5570", target.id)
        assertTrue(target.proxySet)
        assertNull(provisioner.clear(target.id).error)
    }

    @Test
    fun aPhoneIsRecognisedAfterItsWirelessAdbEndpointChanges() {
        val adb = FakeAdb(devices = ONE_PHONE, hardwareSerial = "pixel-hardware-id")
        val provisioner = provisioner(adb = adb)
        assertNull(provisioner.setUp("0A1B2C3D", pem, 9090, lanAddress = "192.168.1.20").error)

        adb.devices = "List of devices attached\n192.168.1.42:5555\tdevice model:Pixel_8"

        val target = provisioner.targets().single()
        assertEquals("192.168.1.42:5555", target.id)
        assertTrue(target.proxySet)
        assertNull(provisioner.clear(target.id).error)
    }

    @Test
    fun rotatingTheRootMarksTheTargetStaleAndRepairRemovesTheOldOwnedRoot() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = provisioner(adb = adb)
        val first = parseCertificate(pem)!!
        assertNull(provisioner.setUp("emulator-5554", pem, 9090).error)
        assertTrue(provisioner.targets(certificateFingerprint(first)).single().certificateCurrent)

        val replacementPem =
            WailoCertificateAuthority(EphemeralCertificateAuthorityStore()).ensure()!!.pem
        val replacement = parseCertificate(replacementPem)!!
        val stale = provisioner.targets(certificateFingerprint(replacement)).single()
        assertFalse(stale.certificateCurrent)
        assertTrue(stale.actionRequired.contains("Repair"))

        val repaired = provisioner.setUp("emulator-5554", replacementPem, 9090)

        assertNull(repaired.error)
        assertTrue(repaired.certificateCurrent)
        assertFalse(adb.files.values.contains(pem))
        assertTrue(adb.files.values.contains(replacementPem))
    }

    @Test
    fun repairingAManuallyTrustedPhoneCallsOutThePreviousRoot() {
        val adb = FakeAdb(devices = ONE_PHONE, rootable = false)
        val provisioner = provisioner(adb = adb)
        assertNull(provisioner.setUp("0A1B2C3D", pem, 9090, "192.168.1.20").error)
        val replacementPem =
            WailoCertificateAuthority(EphemeralCertificateAuthorityStore()).ensure()!!.pem

        val repaired = provisioner.setUp("0A1B2C3D", replacementPem, 9090, "192.168.1.20")

        assertTrue(repaired.actionRequired.contains("remove", ignoreCase = true))
        assertTrue(repaired.actionRequired.contains("previous Wailo root"))
    }

    @Test
    fun provisioningDoesNotOverwriteAnUnownedCertificateWithTheSameSubjectHash() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val otherPem = WailoCertificateAuthority(EphemeralCertificateAuthorityStore()).ensure()!!.pem
        val occupied = "$SYSTEM_CACERTS_FOR_TEST/${subjectHashOld(parseCertificate(pem)!!)}.0"
        adb.files[occupied] = otherPem

        val outcome = provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        assertNull(outcome.error)
        assertEquals(otherPem, adb.files[occupied])
        assertTrue(adb.files.keys.any { it.endsWith(".1") })
    }

    @Test
    fun anEmulatorThatCannotRemountFallsBackToTheUserStoreAndSaysSo() {
        val adb = FakeAdb(devices = ONE_EMULATOR, remountable = false)

        val outcome = provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        assertNull(outcome.error)
        assertTrue(outcome.proxySet)
        assertEquals(CaTrust.USER, outcome.trust)
        assertTrue(adb.commands.any { it.containsAll(listOf("chown", "1000:1000")) })
        assertTrue(
            outcome.note.contains("release build", ignoreCase = true),
            "a user-store install has to say a release build will still refuse: ${outcome.note}",
        )
    }

    @Test
    fun aRemountThatSilentlyDiscardsTheWriteIsNotReportedAsSystemTrust() {
        // The failure this guards: `cp` exits 0 on a partially-remounted /system and the file never lands,
        // which would otherwise be reported as the trust store that makes release builds work.
        val adb = FakeAdb(devices = ONE_EMULATOR, systemWritable = false)

        val outcome = provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        assertEquals(CaTrust.USER, outcome.trust)
    }

    @Test
    fun aPlayStoreImageIsRefusedHonestlyRatherThanReportedAsInstalled() {
        val adb = FakeAdb(devices = ONE_EMULATOR, rootable = false)

        val outcome = provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        // The proxy half still works, which is a useful state: traffic is captured, HTTPS stays a tunnel.
        assertTrue(outcome.proxySet)
        assertEquals(CaTrust.NONE, outcome.trust)
        assertTrue(
            outcome.actionRequired.contains("refuses `adb root`", ignoreCase = true),
            "the result must say why trust still needs a manual step: ${outcome.actionRequired}",
        )
    }

    @Test
    fun theProxyHalfIsRecordedBeforeTheTrustStoreIsTouched() {
        // The proxy setting is the half that strands an emulator with no network, so it has to be undoable
        // even when everything after it fails.
        val adb = FakeAdb(devices = ONE_EMULATOR, rootable = false)

        provisioner(adb = adb).setUp("emulator-5554", pem, 9090)

        assertTrue(Files.readString(statePath).contains("emulator-5554"))
        assertTrue(provisioner(adb = adb).targets().single().proxySet)
    }

    @Test
    fun recoveryClearsAnEmulatorAPreviousDaemonLeftPointedAtAProxyThatIsGone() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        provisioner(adb = adb).setUp("emulator-5554", pem, 9090)
        adb.commands.clear()

        val recovered = provisioner(adb = adb).recover()

        assertTrue(recovered)
        assertTrue(
            adb.commands.any { it.containsAll(listOf("settings", "delete", "global", "http_proxy")) },
            "a stranded emulator has to be given its network back",
        )
        assertFalse(Files.exists(statePath), "a completed recovery must not strand the next boot too")
    }

    @Test
    fun anOfflineTargetStaysVisibleUntilRecoveryCanFinish() {
        val adb = FakeAdb(devices = ONE_EMULATOR)
        val provisioner = provisioner(adb = adb)
        provisioner.setUp("emulator-5554", pem, 9090)
        adb.devices = "List of devices attached"

        assertFalse(provisioner.recover())
        val offline = provisioner.targets().single()
        assertTrue(offline.proxySet)
        assertTrue(offline.actionRequired.contains("Reconnect"))
        assertTrue(Files.exists(statePath))

        adb.devices = ONE_EMULATOR
        assertTrue(provisioner.recover())
        assertFalse(Files.exists(statePath))
    }

    @Test
    fun recoveryIsAQuietNoOpWhenNothingWasProvisioned() {
        assertFalse(provisioner(adb = FakeAdb(devices = ONE_EMULATOR)).recover())
    }

    @Test
    fun releasingATargetWailoNeverSetUpSaysSoRatherThanRunningCommands() {
        val adb = FakeAdb(devices = ONE_EMULATOR)

        val outcome = provisioner(adb = adb).clear("emulator-5554")

        assertNotNull(outcome.error)
        assertTrue(adb.commands.none { it.contains("settings") })
    }

    @Test
    fun bootedSimulatorsAreReadFromSimctlsPlainListing() {
        val simctl = DeviceTool {
            """
            == Devices ==
            -- iOS 17.2 --
                iPhone 15 Pro (A1B2C3D4-1111-2222-3333-444455556666) (Booted)
                iPad Air (B2C3D4E5-1111-2222-3333-444455556666) (Shutdown)
            """.trimIndent()
        }

        val targets = provisioner(simctl = simctl).targets()

        assertEquals(1, targets.size, "a shut-down simulator is not what the user is debugging")
        assertEquals("iPhone 15 Pro", targets.single().name)
        assertEquals("A1B2C3D4-1111-2222-3333-444455556666", targets.single().id)
    }

    @Test
    fun aSimulatorRootIsAddedThroughSimctlAndTheIrreversibilityIsStated() {
        val commands = mutableListOf<List<String>>()
        val simctl = DeviceTool { arguments ->
            commands += arguments
            if (arguments.contains("list")) BOOTED_SIMULATOR else ""
        }

        val provisioner = provisioner(simctl = simctl)
        val outcome = provisioner.setUp(SIMULATOR_UDID, pem, 9090)

        assertNull(outcome.error)
        assertEquals(CaTrust.SYSTEM, outcome.trust)
        assertTrue(commands.any { it.containsAll(listOf("simctl", "keychain", SIMULATOR_UDID, "add-root-cert")) })

        // `simctl` can add a root but not remove one, and resetting the whole keychain would take the
        // simulator's app credentials with it — so releasing has to say the certificate stays.
        val released = provisioner.clear(SIMULATOR_UDID)
        assertTrue(
            released.note.contains("stays in", ignoreCase = true),
            "releasing must not imply the certificate was removed: ${released.note}",
        )
    }

    @Test
    fun theTrustStoreFilenameIsOpensslsLegacySubjectHash() {
        val certificate = parseCertificate(pem)!!

        val hash = subjectHashOld(certificate)

        assertTrue(hash.matches(Regex("[0-9a-f]{8}")), "Android reads an eight-digit lowercase name: $hash")
        // Read back the other way round, so a refactor to big-endian (or to the newer canonical SHA-1
        // hash) fails here rather than silently producing a file Android never reads. `openssl x509
        // -subject_hash_old` is the real oracle for the value itself.
        val digest = MessageDigest.getInstance("MD5").digest(certificate.subjectX500Principal.encoded)
        val expected = ByteBuffer.wrap(digest, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        assertEquals("%08x".format(expected), hash)
    }

    private companion object {
        const val ONE_EMULATOR = "List of devices attached\nemulator-5554\tdevice model:sdk_gphone64_arm64"
        const val ONE_PHONE = "List of devices attached\n0A1B2C3D\tdevice model:Pixel_8"
        const val SYSTEM_CACERTS_FOR_TEST = "/system/etc/security/cacerts"
        const val SIMULATOR_UDID = "A1B2C3D4-1111-2222-3333-444455556666"
        val BOOTED_SIMULATOR = "== Devices ==\n-- iOS 17.2 --\n    iPhone 15 Pro ($SIMULATOR_UDID) (Booted)"
    }
}

/**
 * A stand-in adb. [rootable] is the `google_play` fork, [remountable] whether `/system` opens at all, and
 * [systemWritable] the nastier case where it opens and then discards the write — which is why the real
 * code confirms the copy instead of trusting `cp`'s exit code.
 */
private class FakeAdb(
    var devices: String = "",
    var rootable: Boolean = true,
    private val remountable: Boolean = true,
    private val systemWritable: Boolean = true,
    private val hardwareSerial: String = "hardware-device",
    initialProxy: String? = null,
) : DeviceTool {
    val commands = mutableListOf<List<String>>()
    val files = mutableMapOf<String, String>()
    var proxy: String? = initialProxy

    override fun run(arguments: List<String>): String? {
        commands += arguments
        if (arguments.firstOrNull() == "devices") return devices
        val serial = arguments.getOrNull(1).orEmpty()
        val tail = if (arguments.firstOrNull() == "-s") arguments.drop(2) else arguments
        return when {
            tail.firstOrNull() == "root" ->
                if (rootable) "restarting adbd as root" else "adbd cannot run as root in production builds"
            tail.firstOrNull() == "remount" -> if (remountable) "remount succeeded" else null
            tail.firstOrNull() == "wait-for-device" -> ""
            tail.firstOrNull() == "push" -> {
                files[tail.last()] = runCatching { Files.readString(Path.of(tail[1])) }.getOrDefault("")
                "1 file pushed"
            }
            tail.take(2) == listOf("shell", "cp") -> {
                val source = tail[2]
                val destination = tail.last()
                if (destination.startsWith("/system") && !systemWritable) return ""
                files[source]?.let { files[destination] = it }
                ""
            }
            tail.take(2) == listOf("shell", "cat") -> files[tail.last()]
            tail.take(2) == listOf("shell", "rm") -> "".also { files.remove(tail.last()) }
            tail.take(2) == listOf("shell", "getprop") -> when (tail.last()) {
                "ro.kernel.qemu" -> if (serial.startsWith("emulator-")) "1" else "0"
                "ro.boot.qemu.avd_name" -> "Pixel_API_34"
                "ro.build.version.sdk" -> "34"
                "ro.serialno" -> hardwareSerial
                else -> ""
            }
            tail.take(5) == listOf("shell", "settings", "get", "global", "http_proxy") ->
                proxy ?: "null"
            tail.take(5) == listOf("shell", "settings", "put", "global", "http_proxy") ->
                "".also { proxy = tail.last() }
            tail.take(5) == listOf("shell", "settings", "delete", "global", "http_proxy") ->
                "".also { proxy = null }
            tail.take(3) == listOf("shell", "sh", "-c") -> {
                val path = Regex("'([^']+)'").find(tail.last())?.groupValues?.get(1)
                "absent".takeIf { path != null && path !in files }
            }
            else -> ""
        }
    }
}
