package com.venbiasa.wailo.sdk.android

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Where the device's half of every pairing lives — one entry per Studio, keyed by `studio_id`.
 *
 * Behind [WailoKeyValueStore] rather than touching `SharedPreferences` directly so the handshake is
 * unit-testable, and sealed by the Android Keystore in the shipping configuration ([Wailo.attach]).
 *
 * The record is `|`-separated with every non-numeric field Base64-encoded, the same primitive-KV idiom
 * the studio's layout codecs use (ADR-0019/0026) — a line whose shape does not parse is dropped rather
 * than failing the load, which is what makes adding a field backward-compatible. iOS took the same
 * "drop rather than crash" stance when its record gained `trustedOnFirstUse` (ADR-0040).
 */
internal object WailoPairingStore {

    @Volatile
    var storage: WailoKeyValueStore = InMemoryKeyValueStore()

    fun pairing(studioId: String): WailoPairing? = storage.get(studioId)?.let(::decode)

    fun save(pairing: WailoPairing) {
        storage.put(pairing.studioId, encode(pairing))
    }

    fun forget(studioId: String) {
        storage.put(studioId, null)
    }

    fun forgetAll() {
        for (key in storage.keys()) storage.put(key, null)
    }

    fun all(): List<WailoPairing> =
        storage.keys().mapNotNull { pairing(it) }.sortedBy { it.studioId }

    private fun encode(pairing: WailoPairing): String = listOf(
        pairing.studioId,
        pairing.deviceKey.toByteString().base64(),
        pairing.publicKey.toByteString().base64(),
        pairing.sessionCounter.toString(),
        if (pairing.refused) "1" else "0",
        pairing.lastHost.toByteArray().toByteString().base64(),
        if (pairing.trustedOnFirstUse) "1" else "0",
    ).joinToString(SEPARATOR)

    private fun decode(line: String): WailoPairing? {
        val parts = line.split(SEPARATOR)
        if (parts.size != FIELDS) return null
        return runCatching {
            WailoPairing(
                studioId = parts[0],
                deviceKey = parts[1].decodeBase64()!!.toByteArray(),
                publicKey = parts[2].decodeBase64()!!.toByteArray(),
                sessionCounter = parts[3].toLong(),
                refused = parts[4] == "1",
                lastHost = String(parts[5].decodeBase64()!!.toByteArray()),
                trustedOnFirstUse = parts[6] == "1",
            )
        }.getOrNull()
    }

    private const val SEPARATOR = "|"
    private const val FIELDS = 7
}
