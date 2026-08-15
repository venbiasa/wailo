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
 * than failing the load. V3 deliberately changed that shape so records containing the old global
 * device id cannot be mistaken for Studio-scoped aliases (ADR-0060).
 */
internal object WailoPairingStore {

    @Volatile
    var storage: WailoKeyValueStore = InMemoryKeyValueStore()

    fun pairing(studioId: String): WailoPairing? = storage.get(studioId)?.let(::decode)

    fun save(pairing: WailoPairing) {
        storage.put(pairing.studioId, encode(pairing))
    }

    fun newDeviceAlias(): String =
        WailoCrypto.randomNonce().copyOf(16).toByteString().hex()

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
        pairing.deviceAlias,
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
            require(
                parts[1].length == DEVICE_ALIAS_LENGTH &&
                    parts[1].all { it in '0'..'9' || it in 'a'..'f' },
            )
            WailoPairing(
                studioId = parts[0],
                deviceAlias = parts[1],
                deviceKey = parts[2].decodeBase64()!!.toByteArray(),
                publicKey = parts[3].decodeBase64()!!.toByteArray(),
                sessionCounter = parts[4].toLong(),
                refused = parts[5] == "1",
                lastHost = String(parts[6].decodeBase64()!!.toByteArray()),
                trustedOnFirstUse = parts[7] == "1",
            )
        }.getOrNull()
    }

    private const val SEPARATOR = "|"
    // The extra alias is also the clean v3 migration: seven-field v2 records no longer decode.
    private const val FIELDS = 8
    private const val DEVICE_ALIAS_LENGTH = 32
}
