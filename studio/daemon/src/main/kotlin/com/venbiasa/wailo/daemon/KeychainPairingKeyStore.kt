package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.pairing.PairedDevice
import com.venbiasa.wailo.engine.pairing.PairingKeyStore
import com.venbiasa.wailo.engine.pairing.StoredIdentity
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Persists the daemon's identity and paired-device secrets in the macOS login Keychain. The service
 * name remains unchanged so moving ownership out of Studio does not unpair existing devices.
 */
internal class KeychainPairingKeyStore(
    private val service: String = "com.venbiasa.wailo.studio",
) : PairingKeyStore {
    override fun loadIdentity(): StoredIdentity? {
        val raw = read(IDENTITY_ACCOUNT) ?: return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        return runCatching {
            StoredIdentity(
                privateKeyPkcs8 = Base64.getDecoder().decode(parts[0]),
                publicKeyX963 = Base64.getDecoder().decode(parts[1]),
            )
        }.getOrNull()
    }

    override fun saveIdentity(identity: StoredIdentity) {
        val encoder = Base64.getEncoder()
        write(
            IDENTITY_ACCOUNT,
            "${encoder.encodeToString(identity.privateKeyPkcs8)}:${encoder.encodeToString(identity.publicKeyX963)}",
        )
    }

    override fun loadDevices(): List<PairedDevice> =
        (read(DEVICES_ACCOUNT) ?: return emptyList())
            .lineSequence()
            .mapNotNull(::decodeDevice)
            .sortedBy { it.deviceId }
            .toList()

    override fun saveDevice(device: PairedDevice) {
        writeDevices(loadDevices().filterNot { it.deviceId == device.deviceId } + device)
    }

    override fun removeDevice(deviceId: String) {
        writeDevices(loadDevices().filterNot { it.deviceId == deviceId })
    }

    override fun removeAllDevices() {
        writeDevices(emptyList())
    }

    private fun writeDevices(devices: List<PairedDevice>) {
        write(DEVICES_ACCOUNT, devices.joinToString("\n", transform = ::encodeDevice))
    }

    private fun encodeDevice(device: PairedDevice): String = listOf(
        device.deviceId,
        Base64.getEncoder().encodeToString(device.key),
        Base64.getEncoder().encodeToString(device.name.toByteArray()),
        device.pairedAtEpochMs.toString(),
        device.lastSeenEpochMs.toString(),
        device.sessionCounter.toString(),
        device.trustedOnFirstUse.toString(),
    ).joinToString("\t")

    private fun decodeDevice(line: String): PairedDevice? {
        val parts = line.split('\t')
        if (parts.size !in 6..7) return null
        return runCatching {
            PairedDevice(
                deviceId = parts[0],
                key = Base64.getDecoder().decode(parts[1]),
                name = String(Base64.getDecoder().decode(parts[2])),
                pairedAtEpochMs = parts[3].toLong(),
                lastSeenEpochMs = parts[4].toLong(),
                sessionCounter = parts[5].toLong(),
                trustedOnFirstUse = parts.getOrNull(6)?.toBooleanStrictOrNull() ?: false,
            )
        }.getOrNull()
    }

    private fun read(account: String): String? {
        val result = run("find-generic-password", "-s", service, "-a", account, "-w")
        return result?.trim()?.takeIf { it.isNotEmpty() }?.let { hex ->
            runCatching {
                String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }.getOrNull()
        }
    }

    private fun write(account: String, value: String) {
        run(
            "add-generic-password",
            "-U",
            "-s",
            service,
            "-a",
            account,
            "-D",
            "Wailo pairing",
            "-w",
            value.toByteArray().joinToString("") { "%02x".format(it) },
        )
    }

    private fun run(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("security") + arguments)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        output
    }.getOrNull()

    companion object {
        private const val IDENTITY_ACCOUNT = "studio-identity"
        private const val DEVICES_ACCOUNT = "paired-devices"
        private const val TIMEOUT_SECONDS = 10L

        val isSupported: Boolean
            get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) &&
                File("/usr/bin/security").canExecute()
    }
}
