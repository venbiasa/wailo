package com.venbiasa.wailo.engine.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** One device this Studio has paired with. */
data class PairedDevice(
    val deviceId: String,
    /** Long-term relationship secret. The store must not put this anywhere world-readable. */
    val key: ByteArray,
    /** From the device's `Hello`, so the list reads as device names rather than opaque ids. */
    val name: String,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    /**
     * The highest counter this device has presented. A device that comes back with a lower one is
     * either restored from a backup or a clone; either way it is worth showing rather than swallowing.
     */
    val sessionCounter: Long,
    /**
     * Admitted leniently rather than through a QR or a typed code (ADR-0040). Surfaced because the two
     * carry different guarantees — nobody proved anything on a first contact beyond being reachable at
     * an address the user typed — and a user tightening things up needs to see which is which.
     */
    val trustedOnFirstUse: Boolean = false,
) {
    // Content equality keeps StateFlow from suppressing real metadata/counter updates while avoiding
    // ByteArray's reference equality for records loaded twice from secure storage.
    override fun equals(other: Any?): Boolean =
        other is PairedDevice &&
            other.deviceId == deviceId &&
            other.key.contentEquals(key) &&
            other.name == name &&
            other.pairedAtEpochMs == pairedAtEpochMs &&
            other.lastSeenEpochMs == lastSeenEpochMs &&
            other.sessionCounter == sessionCounter &&
            other.trustedOnFirstUse == trustedOnFirstUse

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + pairedAtEpochMs.hashCode()
        result = 31 * result + lastSeenEpochMs.hashCode()
        result = 31 * result + sessionCounter.hashCode()
        return 31 * result + trustedOnFirstUse.hashCode()
    }
}

/**
 * Where Studio's secrets live. A seam because the engine is headless and must not care: `desktopApp`
 * backs it with the macOS Keychain, and tests with a map.
 *
 * This deliberately does not reuse `KeyValueStore` — that is `java.util.prefs`, which on macOS is a
 * plaintext plist in the user's Library. Fine for a window size, not for a signing key.
 */
interface PairingKeyStore {
    /** Null before the first run, when an identity is generated and saved. */
    fun loadIdentity(): StoredIdentity?
    fun saveIdentity(identity: StoredIdentity)
    fun loadDevices(): List<PairedDevice>
    fun saveDevice(device: PairedDevice)
    fun removeDevice(deviceId: String)
    fun removeAllDevices()
}

/**
 * Both halves of Studio's identity. The public half is stored rather than re-derived because
 * recovering a public key from a P-256 private key needs point multiplication, which the JCA does not
 * expose — and hand-rolling curve arithmetic to save 65 bytes is a bad trade.
 */
data class StoredIdentity(val privateKeyPkcs8: ByteArray, val publicKeyX963: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is StoredIdentity && other.publicKeyX963.contentEquals(publicKeyX963)

    override fun hashCode(): Int = publicKeyX963.contentHashCode()
}

/** The half of Studio's identity that is safe to publish: what a device pins when it pairs. */
interface StudioIdentity {
    /** Fingerprint of [publicKey]; the value in the Bonjour TXT record. */
    val studioId: String
    val publicKey: ByteArray
}

/**
 * An in-progress pairing, and when it stops working.
 *
 * The two channels carry independent secrets rather than one shared value, so neither is capped by
 * the other: a QR can hold a full 256-bit key, while a code short enough to type holds ~50 bits and
 * leans on [WailoCrypto.stretch] plus this expiry to make up the difference. The device says which it
 * used, since Studio has to answer under the right one before it ever sees a proof.
 */
data class PairingOffer(
    val qrSecret: ByteArray,
    /** Ten Crockford-base32 characters. */
    val code: String,
    val expiresAtEpochMs: Long,
) {
    /** The `wailo://pair` URL the QR encodes. Carries the key, so a scan needs nothing from the LAN. */
    fun qrPayload(studioId: String, publicKey: ByteArray, host: String, port: Int): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "wailo://pair?sid=$studioId&h=$host&p=$port" +
            "&k=${encoder.encodeToString(publicKey)}&s=${encoder.encodeToString(qrSecret)}"
    }

    fun secretFor(pairedByCode: Boolean, studioId: String): ByteArray =
        if (pairedByCode) WailoCrypto.stretch(code, studioId) else qrSecret
}

/**
 * Studio's identity and the devices it trusts (ADR-0039).
 *
 * The identity is a P-256 keypair generated once and kept; `studioId` is its fingerprint, which is
 * what makes the Bonjour TXT record self-authenticating — an impostor can advertise the id but cannot
 * sign for it. Resetting the identity is therefore the blunt revocation: every device's pinned key
 * stops matching at once, and they all have to be paired again.
 */
class PairingManager(private val store: PairingKeyStore) {

    private val _identity = MutableStateFlow(Identity(loadOrCreateIdentity()))

    /**
     * Who this Studio is right now. A flow because [resetIdentity] replaces it, and everything derived
     * from it — the Bonjour TXT record, the QR payload — has to follow rather than keep showing a
     * fingerprint no device will accept any more.
     */
    val identity: StateFlow<StudioIdentity> = _identity.asStateFlow()

    /** X9.63 uncompressed point — the form the wire and the QR both carry. */
    val publicKey: ByteArray get() = _identity.value.publicKey

    val studioId: String get() = _identity.value.studioId

    private val _devices = MutableStateFlow(store.loadDevices())
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    private val _offer = MutableStateFlow<PairingOffer?>(null)

    /** The pairing currently on offer, or null when the sheet is closed or the window has passed. */
    val offer: StateFlow<PairingOffer?> = _offer.asStateFlow()

    /**
     * Open a pairing window. Short-lived on purpose: the typed code is only ~50 bits, so the window is
     * the main thing standing between it and an online guessing attempt.
     */
    fun beginPairing(ttlMs: Long = DEFAULT_OFFER_TTL_MS, now: Long = System.currentTimeMillis()): PairingOffer {
        val offer = PairingOffer(
            qrSecret = WailoCrypto.randomSecret(),
            code = PairingCode.encode(WailoCrypto.randomSecret()),
            expiresAtEpochMs = now + ttlMs,
        )
        _offer.value = offer
        return offer
    }

    fun cancelPairing() {
        _offer.value = null
    }

    /** Derive a v3 key from the live offer for a fresh per-Studio alias. */
    fun inviteKeyV3(
        deviceAlias: String,
        pairedByCode: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val offer = _offer.value ?: return null
        if (now >= offer.expiresAtEpochMs) {
            _offer.value = null
            return null
        }
        return WailoCrypto.deviceKeyV3(offer.secretFor(pairedByCode, studioId), studioId, deviceAlias)
    }

    fun known(deviceId: String): PairedDevice? = _devices.value.firstOrNull { it.deviceId == deviceId }

    /**
     * Record a device that has just authenticated. Also closes the offer that admitted it, so one
     * displayed code pairs one device rather than standing open for whoever else saw the screen.
     */
    fun remember(
        deviceId: String,
        key: ByteArray,
        name: String,
        sessionCounter: Long,
        trustedOnFirstUse: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = known(deviceId)
        val device = PairedDevice(
            deviceId = deviceId,
            key = key,
            name = name.ifBlank { existing?.name.orEmpty() },
            pairedAtEpochMs = existing?.pairedAtEpochMs ?: now,
            lastSeenEpochMs = now,
            sessionCounter = maxOf(sessionCounter, existing?.sessionCounter ?: 0L),
            // Trust provenance is immutable relationship metadata. A later metadata-only refresh (for
            // example, learning the human-readable name from Hello) must not relabel TOFU as invited.
            trustedOnFirstUse = trustedOnFirstUse || existing?.trustedOnFirstUse == true,
        )
        store.saveDevice(device)
        _devices.update { list -> list.filterNot { it.deviceId == deviceId } + device }
        if (existing == null) _offer.value = null
    }

    /** Stop trusting one device. It cannot reconnect until it is paired again. */
    fun forget(deviceId: String) {
        store.removeDevice(deviceId)
        _devices.update { list -> list.filterNot { it.deviceId == deviceId } }
    }

    fun forgetAll() {
        store.removeAllDevices()
        _devices.value = emptyList()
    }

    /**
     * Throw away this Studio's identity and start over. Every paired device pinned the old public key,
     * so all of them stop connecting at once — the answer to "assume the whole key store leaked", where
     * forgetting devices one at a time is not enough. Rotates in place so that callers holding this
     * manager — and the flows they are collecting — stay valid; they follow [identity] instead.
     */
    fun resetIdentity() {
        forgetAll()
        cancelPairing()
        val stored = newIdentity()
        store.saveIdentity(stored)
        _identity.value = Identity(stored)
    }

    internal fun signStudioHelloV3(
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): ByteArray {
        val current = _identity.value
        return WailoCrypto.signStudioHelloV3(
            current.privateKey,
            current.studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
        )
    }

    internal fun signResultV3(
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
        resultCode: Int,
    ): ByteArray {
        val current = _identity.value
        return WailoCrypto.signResultV3(
            current.privateKey,
            current.studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
            deviceAlias,
            mode,
            sessionCounter,
            resultCode,
        )
    }

    private fun loadOrCreateIdentity(): StoredIdentity =
        store.loadIdentity() ?: newIdentity().also(store::saveIdentity)

    private fun newIdentity(): StoredIdentity {
        val pair: KeyPair = WailoCrypto.generateIdentity()
        return StoredIdentity(pair.private.encoded, WailoCrypto.encodePublicKey(pair.public))
    }

    private class Identity(stored: StoredIdentity) : StudioIdentity {
        val privateKey: PrivateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(stored.privateKeyPkcs8))
        override val publicKey: ByteArray = stored.publicKeyX963
        override val studioId: String = WailoCrypto.studioId(publicKey)
    }

    companion object {
        const val DEFAULT_OFFER_TTL_MS: Long = 120_000
    }
}
