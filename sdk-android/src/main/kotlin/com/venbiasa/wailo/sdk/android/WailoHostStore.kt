package com.venbiasa.wailo.sdk.android

/**
 * The device's non-secret desktop target settings. Separate from [WailoPairingStore] because route
 * metadata is worth keeping even when the Keystore refuses to seal anything (see [SealedKeyValueStore]).
 *
 * The address is validated on the way in *and* on the way out. Writing is not the only way a value
 * gets here — the debug panel's launcher activity accepts one over `adb` (the Android answer to iOS's
 * `-WailoHost` launch argument) — and text that names no diallable address has to read as "no
 * override" rather than reach the transport (ADR-0035).
 */
internal object WailoHostStore {

    @Volatile
    var storage: WailoKeyValueStore = InMemoryKeyValueStore()

    var host: String?
        get() = storage.get(KEY_HOST)?.let { WailoAddress.parse(it)?.host }
        set(value) {
            storage.put(KEY_HOST, value?.let { WailoAddress.parse(it)?.host })
        }

    var port: Int?
        get() = storage.get(KEY_PORT)?.toIntOrNull()?.takeIf(WailoAddress.PORT_RANGE::contains)
        set(value) {
            storage.put(KEY_PORT, value?.takeIf(WailoAddress.PORT_RANGE::contains)?.toString())
        }

    var expectedStudioId: String?
        get() = storage.get(KEY_EXPECTED_STUDIO_ID)?.takeIf { it.isNotEmpty() }
        set(value) {
            storage.put(KEY_EXPECTED_STUDIO_ID, value?.takeIf { it.isNotEmpty() })
        }

    fun clear() {
        host = null
        port = null
        expectedStudioId = null
    }

    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_EXPECTED_STUDIO_ID = "expected_studio_id"
}
