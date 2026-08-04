package com.venbiasa.wailo.engine.pairing

import java.util.concurrent.ConcurrentHashMap

/**
 * A [PairingKeyStore] that forgets everything on exit. The engine's default, so a test or a headless
 * embedding gets a throwaway identity instead of touching the user's Keychain. The desktop supplies a
 * persistent one — an identity that changed every launch would force every device to pair again.
 */
class InMemoryPairingKeyStore : PairingKeyStore {

    private var identity: StoredIdentity? = null
    private val devices = ConcurrentHashMap<String, PairedDevice>()

    override fun loadIdentity(): StoredIdentity? = identity

    override fun saveIdentity(identity: StoredIdentity) {
        this.identity = identity
    }

    override fun loadDevices(): List<PairedDevice> = devices.values.sortedBy { it.deviceId }

    override fun saveDevice(device: PairedDevice) {
        devices[device.deviceId] = device
    }

    override fun removeDevice(deviceId: String) {
        devices.remove(deviceId)
    }

    override fun removeAllDevices() {
        devices.clear()
    }
}
