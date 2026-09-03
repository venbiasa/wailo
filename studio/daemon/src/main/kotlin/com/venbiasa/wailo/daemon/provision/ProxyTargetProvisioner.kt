package com.venbiasa.wailo.daemon.provision

import com.venbiasa.wailo.daemon.DaemonJson
import com.venbiasa.wailo.daemon.adb.AdbClient
import com.venbiasa.wailo.daemon.adb.parseDevices
import com.venbiasa.wailo.daemon.runMachineProcess
import com.venbiasa.wailo.daemon.wailoMachineStateDir
import com.venbiasa.wailo.daemon.writeAtomically
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class ProxyTargetKind {
    IOS_SIMULATOR,
    ANDROID_EMULATOR,
    ANDROID_DEVICE,
}

/** Only [SYSTEM] is trusted by apps that did not opt into user certificates. */
enum class CaTrust {
    NONE,
    USER,
    SYSTEM,
}

data class ProxyTarget(
    val id: String,
    val name: String,
    val kind: ProxyTargetKind,
    internal val stableId: String = id,
    /** Whether Wailo owns this target's route; simulators use the Mac system proxy. */
    val proxySet: Boolean = false,
    val trust: CaTrust = CaTrust.NONE,
    val certificateCurrent: Boolean = false,
    val cleanupPending: Boolean = false,
    val actionRequired: String = "",
    val detail: String = "",
)

/** [supported] distinguishes missing tooling from no currently connected targets. */
data class ProxyTargetScan(
    val supported: Boolean,
    val targets: List<ProxyTarget> = emptyList(),
    val error: String? = null,
)

/** The confirmed route and trust state after a setup or teardown attempt. */
data class ProxyTargetOutcome(
    val target: ProxyTarget? = null,
    val proxySet: Boolean = false,
    val trust: CaTrust = CaTrust.NONE,
    val certificateCurrent: Boolean = false,
    val cleanupPending: Boolean = false,
    val actionRequired: String = "",
    val note: String = "",
    val error: String? = null,
)

/** Runs one device command; null means failure or timeout. Tests inject this machine-mutation seam. */
fun interface DeviceTool {
    fun run(arguments: List<String>): String?
}

@Serializable
internal data class ProvisionedTarget(
    val id: String,
    val kind: String,
    val name: String,
    val proxySet: Boolean,
    val trust: String,
    val stableId: String = id,
    val previousProxy: String? = null,
    val caFingerprint: String = "",
    val cleanupPending: Boolean = false,
    val certPath: String = "",
    val certPaths: List<String> = emptyList(),
    val actionRequired: String = "",
)

/**
 * Daemon-owned proxy target provisioning (ADR-0090). Its recovery record is written before mutation and
 * stored as machine state because target routes outlive both the daemon and `WAILO_HOME`.
 */
class ProxyTargetProvisioner private constructor(
    private val statePath: Path,
    private val adb: DeviceTool?,
    private val simctl: DeviceTool?,
    private val workDir: Path,
) {
    val supported: Boolean get() = adb != null || simctl != null

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun targets(currentCaFingerprint: String = ""): List<ProxyTarget> {
        val record = readRecord() ?: return detectedTargets()
        val detected = detectedTargets()
        val live = detected.map { target ->
            val known = record.firstOrNull {
                it.kind == target.kind.name && it.stableId == target.stableId
            } ?: return@map target
            val trust = runCatching { CaTrust.valueOf(known.trust) }.getOrDefault(CaTrust.NONE)
            target.copy(
                proxySet = known.proxySet,
                trust = trust,
                certificateCurrent = trust != CaTrust.NONE &&
                    currentCaFingerprint.isNotEmpty() &&
                    known.caFingerprint == currentCaFingerprint,
                cleanupPending = known.cleanupPending,
                actionRequired = actionFor(known, currentCaFingerprint, connected = true),
            )
        }
        val offline = record
            .filter { known ->
                (known.proxySet || known.cleanupPending) &&
                    detected.none { it.kind.name == known.kind && it.stableId == known.stableId }
            }
            .mapNotNull { known ->
                val kind = runCatching { ProxyTargetKind.valueOf(known.kind) }.getOrNull()
                    ?: return@mapNotNull null
                val trust = runCatching { CaTrust.valueOf(known.trust) }.getOrDefault(CaTrust.NONE)
                ProxyTarget(
                    id = known.id,
                    name = known.name,
                    kind = kind,
                    stableId = known.stableId,
                    proxySet = known.proxySet,
                    trust = trust,
                    certificateCurrent = trust != CaTrust.NONE &&
                        currentCaFingerprint.isNotEmpty() &&
                        known.caFingerprint == currentCaFingerprint,
                    cleanupPending = known.cleanupPending,
                    actionRequired = actionFor(known, currentCaFingerprint, connected = false),
                    detail = "Not currently connected",
                )
            }
        return live + offline
    }

    @Synchronized
    fun setUp(
        id: String,
        pem: String,
        proxyPort: Int,
        lanAddress: String = "",
    ): ProxyTargetOutcome {
        val entries = readRecord()
            ?: return ProxyTargetOutcome(error = lastError ?: "The proxy target record could not be read.")
        val target = detectedTargets().firstOrNull { it.id == id }
            ?: return ProxyTargetOutcome(error = "No configurable target called $id is connected.")
        val certificate = parseCertificate(pem)
            ?: return ProxyTargetOutcome(target = target, error = "Wailo's certificate could not be read.")
        val fingerprint = certificateFingerprint(certificate)
        entries.firstOrNull {
            it.kind == target.kind.name && it.stableId == target.stableId && it.cleanupPending
        }?.let { pending ->
            return outcome(
                target,
                pending,
                fingerprint,
                actionRequired = "Release this target to finish the pending cleanup, then set it up again.",
                error = "This target still has an incomplete earlier change.",
            )
        }
        val known = entries.firstOrNull {
            it.kind == target.kind.name && it.stableId == target.stableId && it.proxySet
        }
        if (known != null) {
            return when (target.kind) {
                ProxyTargetKind.IOS_SIMULATOR -> {
                    if (isCertificateCurrent(known, fingerprint)) {
                        outcome(
                            target,
                            known,
                            fingerprint,
                            note = "This target is already routed through Wailo.",
                        )
                    } else {
                        setUpSimulator(target, pem, fingerprint, entries)
                    }
                }
                ProxyTargetKind.ANDROID_EMULATOR,
                ProxyTargetKind.ANDROID_DEVICE,
                -> {
                    val host = if (target.kind == ProxyTargetKind.ANDROID_EMULATOR) {
                        EMULATOR_HOST_ALIAS
                    } else {
                        lanAddress
                    }
                    if (host.isBlank()) {
                        ProxyTargetOutcome(
                            target = target,
                            proxySet = true,
                            trust = runCatching { CaTrust.valueOf(known.trust) }
                                .getOrDefault(CaTrust.NONE),
                            error = "This machine has no LAN address the Android device can reach.",
                        )
                    } else if (!writeAndroidProxy(target.id, "$host:$proxyPort")) {
                        outcome(
                            target,
                            known,
                            fingerprint,
                            error = "Could not confirm the target's proxy route.",
                        )
                    } else if (isCertificateCurrent(known, fingerprint)) {
                        outcome(
                            target,
                            known,
                            fingerprint,
                            note = "This target is already routed through Wailo.",
                        )
                    } else {
                        repairAndroid(target, pem, certificate, fingerprint, known, entries)
                    }
                }
            }
        }
        return when (target.kind) {
            ProxyTargetKind.IOS_SIMULATOR ->
                setUpSimulator(target, pem, fingerprint, entries)
            ProxyTargetKind.ANDROID_EMULATOR ->
                setUpAndroid(target, pem, certificate, fingerprint, EMULATOR_HOST_ALIAS, proxyPort, entries)
            ProxyTargetKind.ANDROID_DEVICE -> {
                if (lanAddress.isBlank()) {
                    ProxyTargetOutcome(
                        target = target,
                        error = "This machine has no LAN address the Android device can reach.",
                    )
                } else {
                    setUpAndroid(target, pem, certificate, fingerprint, lanAddress, proxyPort, entries)
                }
            }
        }
    }

    @Synchronized
    fun clear(id: String): ProxyTargetOutcome {
        val entries = readRecord()
            ?: return ProxyTargetOutcome(error = lastError ?: "The proxy target record could not be read.")
        val live = detectedTargets()
        val direct = live.firstOrNull { it.id == id }
        val known = entries.firstOrNull {
            it.id == id || (direct != null && it.kind == direct.kind.name && it.stableId == direct.stableId)
        }
            ?: return ProxyTargetOutcome(error = "Wailo has not set $id up.")
        val current = direct ?: live.firstOrNull {
            it.kind.name == known.kind && it.stableId == known.stableId
        }
        val kind = runCatching { ProxyTargetKind.valueOf(known.kind) }.getOrNull()
            ?: return ProxyTargetOutcome(error = "Unrecognised target kind ${known.kind}.")
        val target = current ?: ProxyTarget(known.id, known.name, kind, stableId = known.stableId)
        return when (kind) {
            ProxyTargetKind.IOS_SIMULATOR -> {
                val retained = known.copy(proxySet = false, cleanupPending = false)
                if (!replace(entries, known, retained)) {
                    return ProxyTargetOutcome(target = target, error = "Could not update the target recovery record.")
                }
                outcome(
                    target,
                    retained,
                    currentFingerprint = "",
                    note = "Stopped sending this Mac's traffic through Wailo. The certificate stays in " +
                        "the simulator's trust store — `simctl` can add a root but not remove one, so " +
                        "erasing that simulator is the only way to take it back out.",
                )
            }
            ProxyTargetKind.ANDROID_EMULATOR,
            ProxyTargetKind.ANDROID_DEVICE,
            -> clearAndroid(target, known, entries)
        }
    }

    @Synchronized
    fun recover(): Boolean {
        val entries = readRecord() ?: return false
        val pending = entries.filter { it.proxySet || it.cleanupPending }
        if (pending.isEmpty()) return false
        val live = detectedTargets()
        var recovered = false
        pending.forEach { known ->
            val kind = runCatching { ProxyTargetKind.valueOf(known.kind) }.getOrNull() ?: return@forEach
            if (kind == ProxyTargetKind.IOS_SIMULATOR) {
                val retained = known.copy(proxySet = false)
                if (replace(readRecord() ?: return recovered, known, retained)) recovered = true
                return@forEach
            }
            val target = live.firstOrNull {
                it.kind == kind && it.stableId == known.stableId
            } ?: return@forEach
            val outcome = clear(target.id)
            if (outcome.error == null) recovered = true
        }
        return recovered
    }

    @Synchronized
    fun activeTargets(): List<ProxyTarget> {
        val entries = readRecord() ?: return emptyList()
        return entries.filter { it.proxySet }.mapNotNull { known ->
            val kind = runCatching { ProxyTargetKind.valueOf(known.kind) }.getOrNull() ?: return@mapNotNull null
            ProxyTarget(known.id, known.name, kind, stableId = known.stableId, proxySet = true)
        }
    }

    @Synchronized
    fun recoverableTargets(): List<ProxyTarget> {
        val entries = readRecord() ?: return emptyList()
        return entries.filter { it.proxySet || it.cleanupPending }.mapNotNull { known ->
            val kind = runCatching { ProxyTargetKind.valueOf(known.kind) }.getOrNull() ?: return@mapNotNull null
            ProxyTarget(known.id, known.name, kind, stableId = known.stableId, proxySet = known.proxySet)
        }
    }

    fun clearAll(): List<ProxyTargetOutcome> = recoverableTargets().map { clear(it.id) }

    private fun setUpSimulator(
        target: ProxyTarget,
        pem: String,
        fingerprint: String,
        entries: List<ProvisionedTarget>,
    ): ProxyTargetOutcome {
        val replacingRoot = entries.any {
            it.kind == target.kind.name &&
                it.stableId == target.stableId &&
                it.caFingerprint.isNotEmpty() &&
                it.caFingerprint != fingerprint
        }
        val simctl = simctl
            ?: return ProxyTargetOutcome(target = target, error = "No `xcrun simctl` on this machine.")
        val prepared = ProvisionedTarget(
            id = target.id,
            kind = target.kind.name,
            name = target.name,
            stableId = target.stableId,
            proxySet = true,
            trust = CaTrust.NONE.name,
            caFingerprint = fingerprint,
        )
        if (!replaceForTarget(entries, target, prepared)) {
            return ProxyTargetOutcome(target = target, error = "Could not record how to release this simulator.")
        }
        val file = spill(pem, "wailo-root.pem")
            ?: run {
                readRecord()?.let { replace(it, prepared, prepared.copy(proxySet = false)) }
                return ProxyTargetOutcome(target = target, error = "Could not write the certificate out.")
            }
        return try {
            val added = simctl.run(listOf("simctl", "keychain", target.id, "add-root-cert", file.toString()))
            if (added == null) {
                readRecord()?.let { replace(it, prepared, prepared.copy(proxySet = false)) }
                ProxyTargetOutcome(target = target, error = "`simctl keychain add-root-cert` failed.")
            } else {
                val installed = prepared.copy(trust = CaTrust.SYSTEM.name)
                if (!replace(readRecord().orEmpty(), prepared, installed)) {
                    return ProxyTargetOutcome(
                        target = target,
                        proxySet = true,
                        trust = CaTrust.SYSTEM,
                        certificateCurrent = true,
                        cleanupPending = true,
                        error = "The root was added, but its recovery record could not be updated.",
                    )
                }
                outcome(
                    target,
                    installed,
                    fingerprint,
                    note = "Trusted as a system root, so apps in this simulator trust it without opting " +
                        "in. Already-running apps keep their old trust store until they relaunch." +
                        if (replacingRoot) {
                            " The earlier Wailo root also remains because simctl cannot remove one root; " +
                                "erase the simulator to remove it."
                        } else {
                            ""
                        },
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(file) }
        }
    }

    private fun simulators(): List<ProxyTarget> {
        val output = simctl?.run(listOf("simctl", "list", "devices", "booted")) ?: return emptyList()
        return parseBootedSimulators(output).map {
            ProxyTarget(
                id = it.udid,
                name = it.name,
                kind = ProxyTargetKind.IOS_SIMULATOR,
                stableId = "ios-simulator:${it.udid}",
                detail = "Booted",
            )
        }
    }

    private fun setUpAndroid(
        target: ProxyTarget,
        pem: String,
        certificate: X509Certificate,
        fingerprint: String,
        host: String,
        proxyPort: Int,
        entries: List<ProvisionedTarget>,
    ): ProxyTargetOutcome {
        if (adb == null) return ProxyTargetOutcome(target = target, error = "No adb on this machine.")
        val previous = readAndroidProxy(target.id)
            ?: return ProxyTargetOutcome(target = target, error = "Could not read the target's current proxy.")
        val prepared = ProvisionedTarget(
            id = target.id,
            kind = target.kind.name,
            name = target.name,
            stableId = target.stableId,
            previousProxy = previous.value,
            proxySet = true,
            trust = CaTrust.NONE.name,
            caFingerprint = fingerprint,
            cleanupPending = true,
        )
        if (!replaceForTarget(entries, target, prepared)) {
            return ProxyTargetOutcome(target = target, error = "Could not record the target's current proxy.")
        }
        val wanted = "$host:$proxyPort"
        if (!writeAndroidProxy(target.id, wanted)) {
            val restored = restoreAndroidProxy(target.id, previous)
            if (restored) forget(prepared)
            return ProxyTargetOutcome(
                target = target,
                proxySet = !restored,
                cleanupPending = !restored,
                error = if (restored) {
                    "Could not set the target's proxy; its previous setting was restored."
                } else {
                    "Could not set or restore the target's proxy; recovery remains pending."
                },
            )
        }

        var working = prepared
        val installed = installAndroidCertificate(target, pem, certificate, fingerprint) { path ->
            val planned = working.copy(
                certPath = path,
                certPaths = (working.ownedCertificatePaths() + path).distinct(),
            )
            val current = readRecord()
            if (current == null) {
                false
            } else {
                replace(current, working, planned).also { saved ->
                    if (saved) working = planned
                }
            }
        }
        val completed = working.copy(
            trust = installed.trust.name,
            certPath = installed.path.ifEmpty { working.certPath },
            certPaths = (working.ownedCertificatePaths() + installed.path)
                .filter { it.isNotEmpty() }
                .distinct(),
            cleanupPending = installed.error != null,
            actionRequired = installed.actionRequired,
        )
        val latest = readRecord()
        val saved = latest != null && replace(latest, working, completed)
        return outcome(
            target,
            if (saved) completed else working,
            fingerprint,
            note = listOfNotNull(
                if (target.kind == ProxyTargetKind.ANDROID_EMULATOR) {
                    "Pointed at $wanted through the emulator's host-loopback alias."
                } else {
                    "Pointed at $wanted over this device's current network."
                },
                installed.note.takeIf { it.isNotEmpty() },
            ).joinToString(" "),
            actionRequired = installed.actionRequired,
            error = installed.error ?: "The target was configured, but its recovery record could not be updated."
                .takeUnless { saved },
        )
    }

    private class CertificateInstall(
        val trust: CaTrust,
        val path: String,
        val note: String,
        val actionRequired: String = "",
        val error: String? = null,
    )

    private fun repairAndroid(
        target: ProxyTarget,
        pem: String,
        certificate: X509Certificate,
        fingerprint: String,
        known: ProvisionedTarget,
        entries: List<ProvisionedTarget>,
    ): ProxyTargetOutcome {
        var working = known.copy(id = target.id, name = target.name, cleanupPending = true)
        if (!replace(entries, known, working)) {
            return outcome(
                target,
                known,
                fingerprint,
                error = "Could not record certificate repair before changing the target.",
            )
        }
        val installed = installAndroidCertificate(target, pem, certificate, fingerprint) { path ->
            val planned = working.copy(
                certPath = path,
                certPaths = (working.ownedCertificatePaths() + path).distinct(),
            )
            val current = readRecord()
            if (current == null) {
                false
            } else {
                replace(current, working, planned).also { saved ->
                    if (saved) working = planned
                }
            }
        }
        val actionRequired = listOfNotNull(
            installed.actionRequired.takeIf { it.isNotEmpty() },
            "If you approved the previous Wailo root in Android Settings, remove it there too."
                .takeIf {
                    known.trust == CaTrust.NONE.name &&
                        known.caFingerprint.isNotEmpty() &&
                        known.caFingerprint != fingerprint
                },
        ).joinToString(" ")
        val completed = working.copy(
            trust = installed.trust.name,
            caFingerprint = fingerprint,
            certPath = installed.path.ifEmpty { working.certPath },
            certPaths = (working.ownedCertificatePaths() + installed.path)
                .filter { it.isNotEmpty() }
                .distinct(),
            cleanupPending = installed.error != null,
            actionRequired = actionRequired,
        )
        var error = installed.error
        val current = readRecord()
            ?: return outcome(target, working, fingerprint, error = lastError)
        if (!replace(current, working, completed)) {
            return outcome(
                target,
                working,
                fingerprint,
                error = "The certificate changed, but its recovery record could not be updated.",
            )
        }
        working = completed
        if (installed.error == null) {
            val retained = listOfNotNull(installed.path.takeIf { it.isNotEmpty() }).toSet()
            val removed = known.ownedCertificatePaths()
                .filterNot { it in retained }
                .all { removeOwnedCertificate(target.id, it) }
            val cleaned = working.copy(
                certPath = installed.path,
                certPaths = retained.toList(),
                cleanupPending = !removed,
            )
            val latest = readRecord()
            if (latest == null || !replace(latest, working, cleaned)) {
                error = "The new certificate works, but its cleanup record could not be updated."
            } else if (!removed) {
                working = cleaned
                error = "The new certificate works, but an older Wailo root still needs cleanup."
            } else {
                working = cleaned
            }
        }
        return outcome(
            target,
            working,
            fingerprint,
            note = installed.note,
            actionRequired = actionRequired,
            error = error,
        )
    }

    private fun installAndroidCertificate(
        target: ProxyTarget,
        pem: String,
        certificate: X509Certificate,
        fingerprint: String,
        recordPath: (String) -> Boolean,
    ): CertificateInstall {
        val baseName = subjectHashOld(certificate)
        val staged = "$STAGING_DIR/$baseName.0"
        val file = spill(pem, "$baseName.0")
            ?: return CertificateInstall(CaTrust.NONE, "", "Could not write the certificate out.")
        try {
            if (!recordPath(staged)) {
                return CertificateInstall(
                    CaTrust.NONE,
                    staged,
                    "",
                    error = "Could not record certificate cleanup before copying it.",
                )
            }
            if (adbRun(target.id, "push", file.toString(), staged) == null) {
                return CertificateInstall(CaTrust.NONE, "", "Could not copy the certificate to the emulator.")
            }
            val rooted = adbRun(target.id, "root")
            if (rooted == null || rooted.contains(NOT_ROOTABLE, ignoreCase = true)) {
                val download = "$ANDROID_DOWNLOADS/Wailo-Local-Root.pem"
                if (!recordPath(download)) {
                    return CertificateInstall(
                        CaTrust.NONE,
                        download,
                        "",
                        error = "Could not record certificate cleanup before copying it.",
                    )
                }
                if (adbRun(target.id, "push", file.toString(), download) != null) {
                    val action = "On the device, open Settings → Security → Encryption & credentials → " +
                        "Install a certificate → CA certificate, then choose Wailo-Local-Root.pem."
                    return CertificateInstall(
                        CaTrust.NONE,
                        download,
                        "The certificate is in Downloads, but Android requires you to approve trust.",
                        if (target.kind == ProxyTargetKind.ANDROID_DEVICE) {
                            action
                        } else {
                            "This emulator image refuses `adb root`. $action"
                        },
                    )
                }
                return CertificateInstall(
                    CaTrust.NONE,
                    "",
                    "The Android image refuses `adb root`, and the certificate could not be copied to Downloads.",
                )
            }
            adbRun(target.id, "wait-for-device")

            if (adbRun(target.id, "remount") != null) {
                val path = availableCertificatePath(target.id, SYSTEM_CACERTS, baseName, fingerprint)
                if (path != null && recordPath(path) && copyInto(target.id, staged, path, fingerprint)) {
                    return CertificateInstall(
                        CaTrust.SYSTEM,
                        path,
                        "Installed as a system root, so release builds trust it too.",
                    )
                }
            }
            val userPath = availableCertificatePath(target.id, USER_CACERTS, baseName, fingerprint)
            if (userPath != null && recordPath(userPath) && copyInto(target.id, staged, userPath, fingerprint)) {
                return CertificateInstall(
                    CaTrust.USER,
                    userPath,
                    "Installed as a user root — `/system` would not remount, which is normal on API 34 " +
                        "and up. Debug builds trust it; a release build ignores user certificates " +
                        "unless it opted in, and will keep refusing.",
                )
            }
            return CertificateInstall(
                CaTrust.NONE,
                "",
                "Neither trust store would accept the certificate.",
                error = "Certificate installation failed; cleanup remains pending.",
            )
        } finally {
            runCatching { Files.deleteIfExists(file) }
            adbRun(target.id, "shell", "rm", "-f", staged)
        }
    }

    private fun clearAndroid(
        target: ProxyTarget,
        known: ProvisionedTarget,
        entries: List<ProvisionedTarget>,
    ): ProxyTargetOutcome {
        val previous = AndroidProxy(known.previousProxy)
        if (known.proxySet && !restoreAndroidProxy(target.id, previous)) {
            val pending = known.copy(cleanupPending = true)
            replace(entries, known, pending)
            return outcome(
                target,
                pending,
                currentFingerprint = "",
                error = "Could not restore the target's previous proxy; recovery remains pending.",
            )
        }

        val certificateRemoved = known.ownedCertificatePaths()
            .map { removeOwnedCertificate(target.id, it) }
            .all { it }
        if (!certificateRemoved) {
            val pending = known.copy(proxySet = false, cleanupPending = true)
            replace(entries, known, pending)
            return outcome(
                target,
                pending,
                currentFingerprint = "",
                error = "The proxy was restored, but certificate cleanup remains pending.",
            )
        }
        if (!forget(known)) {
            return ProxyTargetOutcome(
                target = target,
                cleanupPending = true,
                error = "The target was restored, but its recovery record could not be removed.",
            )
        }
        val manualTrustNotice = if (
            known.trust == CaTrust.NONE.name &&
            known.ownedCertificatePaths().any { it.startsWith(ANDROID_DOWNLOADS) }
        ) {
            " If you approved the root in Android Settings, remove it there too; Android does not expose " +
                "that approval to adb."
        } else {
            ""
        }
        return ProxyTargetOutcome(
            target = target,
            note = "Restored the target's previous proxy.$manualTrustNotice",
        )
    }

    private fun copyInto(
        serial: String,
        staged: String,
        path: String,
        fingerprint: String,
    ): Boolean {
        val directory = path.substringBeforeLast('/')
        adbRun(serial, "shell", "mkdir", "-p", directory)
        if (adbRun(serial, "shell", "cp", staged, path) == null) return false
        val owner = if (path.startsWith(USER_CACERTS)) "1000:1000" else "0:0"
        if (adbRun(serial, "shell", "chown", owner, path) == null) return false
        if (adbRun(serial, "shell", "chmod", "644", path) == null) return false
        if (adbRun(serial, "shell", "restorecon", path) == null) return false
        return certificateAt(serial, path) == fingerprint
    }

    private fun availableCertificatePath(
        serial: String,
        directory: String,
        baseName: String,
        fingerprint: String,
    ): String? {
        for (suffix in 0..99) {
            val path = "$directory/$baseName.$suffix"
            if (certificateAt(serial, path) == fingerprint) continue
            val absent = adbRun(serial, "shell", "sh", "-c", "test ! -e '$path' && echo absent")
                ?.contains("absent") == true
            if (absent) return path
        }
        return null
    }

    private fun androidTargets(): List<ProxyTarget> {
        val output = adb?.run(listOf("devices", "-l")) ?: return emptyList()
        return parseDevices(output)
            .filter { it.ready }
            .map { device ->
                val emulator = device.serial.startsWith(EMULATOR_SERIAL_PREFIX) ||
                    adbRun(device.serial, "shell", "getprop", "ro.kernel.qemu")?.trim() == "1"
                val avd = if (emulator) {
                    adbRun(device.serial, "shell", "getprop", "ro.boot.qemu.avd_name")
                        ?.trim()
                        .orEmpty()
                } else {
                    ""
                }
                val hardwareId = if (emulator) {
                    ""
                } else {
                    adbRun(device.serial, "shell", "getprop", "ro.serialno")?.trim().orEmpty()
                }
                ProxyTarget(
                    id = device.serial,
                    name = device.label,
                    kind = if (emulator) ProxyTargetKind.ANDROID_EMULATOR else ProxyTargetKind.ANDROID_DEVICE,
                    stableId = if (emulator && avd.isNotEmpty()) {
                        "android-emulator:$avd"
                    } else if (emulator) {
                        "android-emulator:${device.serial}"
                    } else {
                        "android-device:${hardwareId.ifEmpty { device.serial }}"
                    },
                    detail = listOfNotNull(
                        adbRun(device.serial, "shell", "getprop", "ro.build.version.sdk")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                            ?.let { "API $it" },
                        "ADB connected".takeIf { !emulator },
                    ).joinToString(" · "),
                )
            }
    }

    private fun detectedTargets(): List<ProxyTarget> = simulators() + androidTargets()

    private fun adbRun(serial: String, vararg arguments: String): String? =
        adb?.run(listOf("-s", serial) + arguments)

    private data class AndroidProxy(val value: String?)

    private fun readAndroidProxy(serial: String): AndroidProxy? {
        val output = adbRun(serial, "shell", "settings", "get", "global", "http_proxy") ?: return null
        return AndroidProxy(output.trim().takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) })
    }

    private fun writeAndroidProxy(serial: String, value: String): Boolean =
        adbRun(serial, "shell", "settings", "put", "global", "http_proxy", value) != null &&
            readAndroidProxy(serial)?.value == value

    private fun restoreAndroidProxy(serial: String, previous: AndroidProxy): Boolean {
        val command = if (previous.value == null) {
            adbRun(serial, "shell", "settings", "delete", "global", "http_proxy")
        } else {
            adbRun(serial, "shell", "settings", "put", "global", "http_proxy", previous.value)
        }
        return command != null && readAndroidProxy(serial)?.value == previous.value
    }

    private fun certificateAt(serial: String, path: String): String? =
        adbRun(serial, "shell", "cat", path)
            ?.let(::parseCertificate)
            ?.let(::certificateFingerprint)

    private fun removeOwnedCertificate(serial: String, path: String): Boolean {
        if (path.isEmpty()) return true
        if (path.startsWith(SYSTEM_CACERTS) || path.startsWith(USER_CACERTS)) {
            val rooted = adbRun(serial, "root")
            if (rooted == null || rooted.contains(NOT_ROOTABLE, ignoreCase = true)) return false
            adbRun(serial, "wait-for-device")
            if (path.startsWith(SYSTEM_CACERTS) && adbRun(serial, "remount") == null) return false
        }
        if (adbRun(serial, "shell", "rm", "-f", path) == null) return false
        return adbRun(serial, "shell", "sh", "-c", "test ! -e '$path' && echo absent")
            ?.contains("absent") == true
    }

    private fun spill(pem: String, name: String): Path? = runCatching {
        Files.createDirectories(workDir)
        workDir.resolve(name).also { Files.writeString(it, pem) }
    }.getOrNull()

    private fun ProvisionedTarget.ownedCertificatePaths(): List<String> =
        (certPaths + certPath).filter { it.isNotEmpty() }.distinct()

    private fun isCertificateCurrent(known: ProvisionedTarget, fingerprint: String): Boolean =
        known.caFingerprint == fingerprint &&
            runCatching { CaTrust.valueOf(known.trust) }.getOrDefault(CaTrust.NONE) != CaTrust.NONE &&
            !known.cleanupPending

    private fun actionFor(
        known: ProvisionedTarget,
        currentFingerprint: String,
        connected: Boolean,
    ): String = when {
        !connected -> "Reconnect this target, then retry Release."
        known.cleanupPending -> "Retry the action so Wailo can finish cleanup."
        known.proxySet &&
            known.caFingerprint.isNotEmpty() &&
            currentFingerprint.isNotEmpty() &&
            known.caFingerprint != currentFingerprint ->
            "Wailo's root changed. Repair this target before expecting HTTPS decryption."
        known.actionRequired.isNotEmpty() -> known.actionRequired
        else -> ""
    }

    private fun outcome(
        target: ProxyTarget,
        known: ProvisionedTarget,
        currentFingerprint: String,
        note: String = "",
        actionRequired: String = "",
        error: String? = null,
    ): ProxyTargetOutcome {
        val trust = runCatching { CaTrust.valueOf(known.trust) }.getOrDefault(CaTrust.NONE)
        return ProxyTargetOutcome(
            target = target,
            proxySet = known.proxySet,
            trust = trust,
            certificateCurrent = trust != CaTrust.NONE &&
                currentFingerprint.isNotEmpty() &&
                known.caFingerprint == currentFingerprint,
            cleanupPending = known.cleanupPending,
            actionRequired = actionRequired.ifEmpty { known.actionRequired },
            note = note,
            error = error,
        )
    }

    private fun replaceForTarget(
        entries: List<ProvisionedTarget>,
        target: ProxyTarget,
        replacement: ProvisionedTarget,
    ): Boolean = persist(
        entries.filterNot { it.kind == target.kind.name && it.stableId == target.stableId } + replacement,
    )

    private fun replace(
        entries: List<ProvisionedTarget>,
        known: ProvisionedTarget,
        replacement: ProvisionedTarget,
    ): Boolean {
        if (known !in entries) return false
        return persist(entries.map { if (it == known) replacement else it })
    }

    private fun forget(known: ProvisionedTarget): Boolean {
        val entries = readRecord() ?: return false
        return persist(entries.filterNot { it == known })
    }

    private fun readRecord(): List<ProvisionedTarget>? {
        if (!Files.exists(statePath)) {
            lastError = null
            return emptyList()
        }
        return runCatching {
            DaemonJson.decodeFromString<List<ProvisionedTarget>>(Files.readString(statePath))
        }.onSuccess {
            lastError = null
        }.onFailure {
            lastError = "The proxy target recovery record is unreadable; no device was changed."
        }.getOrNull()
    }

    private fun persist(entries: List<ProvisionedTarget>): Boolean {
        val saved = if (entries.isEmpty()) {
            runCatching { Files.deleteIfExists(statePath) }.isSuccess
        } else {
            writeAtomically(statePath, DaemonJson.encodeToString(entries))
        }
        lastError = "Could not save the proxy target recovery record; no further device changes were made."
            .takeUnless { saved }
        return saved
    }

    companion object {
        private const val EMULATOR_SERIAL_PREFIX = "emulator-"

        /** The emulator's fixed alias for the host machine's loopback interface. */
        internal const val EMULATOR_HOST_ALIAS = "10.0.2.2"

        private const val SYSTEM_CACERTS = "/system/etc/security/cacerts"
        private const val USER_CACERTS = "/data/misc/user/0/cacerts-added"
        private const val STAGING_DIR = "/data/local/tmp"
        private const val ANDROID_DOWNLOADS = "/sdcard/Download"
        private const val NOT_ROOTABLE = "cannot run as root"

        /** The only construction that pairs real device tools with the machine recovery record (ADR-0083). */
        fun forThisMachine(): ProxyTargetProvisioner = ProxyTargetProvisioner(
            statePath = wailoMachineStateDir().resolve("proxy-targets.json"),
            adb = adbTool(),
            simctl = simctlTool(),
            workDir = wailoMachineStateDir().resolve("provision"),
        )

        fun over(
            adb: DeviceTool?,
            simctl: DeviceTool?,
            statePath: Path,
            workDir: Path = statePath.parent.resolve("provision"),
        ): ProxyTargetProvisioner = ProxyTargetProvisioner(statePath, adb, simctl, workDir)
    }
}

internal class SimulatorDevice(val name: String, val udid: String)

private val SIMULATOR_LINE = Regex("^\\s+(.+?)\\s+\\(([0-9A-Fa-f-]{36})\\)\\s+\\(Booted\\)")

/** Parses the stable subset of `simctl list devices booted`; its JSON nesting varies by Xcode. */
internal fun parseBootedSimulators(output: String): List<SimulatorDevice> = output.lines()
    .mapNotNull { SIMULATOR_LINE.find(it) }
    .map { SimulatorDevice(name = it.groupValues[1].trim(), udid = it.groupValues[2]) }

/** Android's CA filename: OpenSSL `X509_NAME_hash_old`, not the newer canonical SHA-1 hash. */
internal fun subjectHashOld(certificate: X509Certificate): String {
    val digest = MessageDigest.getInstance("MD5").digest(certificate.subjectX500Principal.encoded)
    val value = (digest[0].toLong() and 0xff) or
        ((digest[1].toLong() and 0xff) shl 8) or
        ((digest[2].toLong() and 0xff) shl 16) or
        ((digest[3].toLong() and 0xff) shl 24)
    return "%08x".format(value)
}

internal fun parseCertificate(pem: String): X509Certificate? = runCatching {
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
}.getOrNull()

internal fun certificateFingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it) }

private const val TOOL_TIMEOUT_SECONDS = 30L

private const val XCRUN = "/usr/bin/xcrun"

private fun adbTool(): DeviceTool? =
    AdbClient.findExecutable()?.let { processTool(listOf(it.path)) }

private fun simctlTool(): DeviceTool? {
    val onAMac = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
    if (!onAMac) return null
    return File(XCRUN).takeIf { it.canExecute() }?.let { processTool(listOf(it.path)) }
}

internal fun processTool(
    prefix: List<String>,
    timeoutSeconds: Long = TOOL_TIMEOUT_SECONDS,
): DeviceTool = DeviceTool { arguments ->
    runMachineProcess(prefix + arguments, timeoutSeconds)
}
