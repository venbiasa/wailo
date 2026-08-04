package com.venbiasa.wailo.desktop.pairing

import com.venbiasa.wailo.engine.pairing.PairedDevice
import com.venbiasa.wailo.engine.pairing.PairingKeyStore
import com.venbiasa.wailo.engine.pairing.StoredIdentity
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Studio's keys in the macOS login Keychain (ADR-0039).
 *
 * Deliberately not `KeyValueStore`: that is `java.util.prefs`, which on macOS is a plaintext plist
 * under `~/Library/Preferences`. Perfectly fine for a window size, and completely wrong for a signing
 * key and every device's long-term secret — anything that could read that file could impersonate this
 * Studio to every paired device and decrypt their traffic.
 *
 * Driven through the `security` command line rather than JNA-bound Security.framework: the desktop
 * build has no native-interop dependency and adding one for a handful of calls is a poor trade. Every
 * secret is passed as hex through `-w`, which keeps it out of argument parsing surprises but *does*
 * mean it appears in this process's argv while the call runs. That is a local-user exposure only, and
 * the alternative — a file on disk — is worse.
 */
class KeychainPairingKeyStore(
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
        val updated = loadDevices().filterNot { it.deviceId == device.deviceId } + device
        writeDevices(updated)
    }

    override fun removeDevice(deviceId: String) {
        writeDevices(loadDevices().filterNot { it.deviceId == deviceId })
    }

    override fun removeAllDevices() {
        writeDevices(emptyList())
    }

    // All devices live in one Keychain item rather than one item each: the `security` CLI prompts per
    // item on first access, and a prompt per paired device at launch would be intolerable.
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
    ).joinToString("\t")

    private fun decodeDevice(line: String): PairedDevice? {
        val parts = line.split('\t')
        if (parts.size != 6) return null
        return runCatching {
            PairedDevice(
                deviceId = parts[0],
                key = Base64.getDecoder().decode(parts[1]),
                name = String(Base64.getDecoder().decode(parts[2])),
                pairedAtEpochMs = parts[3].toLong(),
                lastSeenEpochMs = parts[4].toLong(),
                sessionCounter = parts[5].toLong(),
            )
        }.getOrNull()
    }

    private fun read(account: String): String? {
        val result = run("find-generic-password", "-s", service, "-a", account, "-w")
        return result?.trim()?.takeIf { it.isNotEmpty() }?.let { hex ->
            runCatching { String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.getOrNull()
        }
    }

    private fun write(account: String, value: String) {
        // `-U` updates in place; without it a second save fails with "item already exists".
        run(
            "add-generic-password", "-U",
            "-s", service,
            "-a", account,
            "-D", "Wailo pairing",
            "-w", value.toByteArray().joinToString("") { "%02x".format(it) },
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

        /**
         * Whether this platform has a Keychain to put secrets in. Everywhere else the desktop refuses
         * WiFi pairing rather than quietly writing long-term keys somewhere world-readable; loopback
         * and USB still work, and those are the paths that never needed a key.
         */
        val isSupported: Boolean
            get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) &&
                File("/usr/bin/security").canExecute()
    }
}
