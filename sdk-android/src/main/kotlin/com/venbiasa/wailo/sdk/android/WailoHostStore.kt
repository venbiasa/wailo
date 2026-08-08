package com.venbiasa.wailo.sdk.android

import okio.ByteString.Companion.toByteString

/**
 * The device's non-secret settings: the desktop address the user pinned, and this install's opaque id.
 *
 * Separate from [WailoPairingStore] because neither of these is a secret and both are worth keeping
 * even when the Keystore refuses to seal anything (see [SealedKeyValueStore]) — in particular the
 * device id, which would otherwise be regenerated on every launch and litter Studio's Devices panel
 * with a new stranger each time.
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

    /**
     * A stable, opaque handle for this install. Opaque on purpose: it is the one identifier that
     * crosses the wire before either side has proved anything, so it must not carry the device's name
     * or the app's package the way `Hello` does.
     */
    val deviceId: String
        get() = synchronized(this) {
            storage.get(KEY_DEVICE_ID) ?: WailoCrypto.randomNonce()
                .copyOf(DEVICE_ID_BYTES)
                .toByteString()
                .hex()
                .also { storage.put(KEY_DEVICE_ID, it) }
        }

    fun clear() {
        host = null
        port = null
    }

    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_DEVICE_ID = "device_id"
    private const val DEVICE_ID_BYTES = 16
}
