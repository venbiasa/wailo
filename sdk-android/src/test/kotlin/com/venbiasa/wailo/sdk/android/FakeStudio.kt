package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthChallenge
import com.venbiasa.wailo.protocol.AuthRequest
import com.venbiasa.wailo.protocol.AuthResponse
import com.venbiasa.wailo.protocol.AuthResult
import com.venbiasa.wailo.protocol.Envelope
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Studio's half of the WiFi handshake, reimplemented from `engine/pairing/SecureChannel.kt` for tests.
 *
 * A copy rather than the real thing because the engine lives in a different Gradle build that this one
 * must never depend on (invariant #3, ADR-0015) — the same reason there are three implementations of
 * `WailoCrypto`. Being a copy is also what makes it useful: it exercises the device against the wire
 * contract rather than against shared code, so a change to either side that breaks the other shows up
 * here rather than on a desk.
 */
internal class FakeStudio {

    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    val publicKey: ByteArray = WailoCrypto.encodePublicKey(keyPair.public)
    val studioId: String = WailoCrypto.studioId(publicKey)

    /** Devices this Studio holds a long-term key for, as `PairingManager` persists them. */
    val known = mutableMapOf<String, ByteArray>()
    val counters = mutableMapOf<String, Long>()

    /**
     * A live pairing window. The two halves carry independent secrets — a QR can hold a full 256-bit
     * key, a typed code holds ~50 bits and leans on [WailoCrypto.stretch] — and the device says which
     * one it used, so Studio has to answer under the right one before it ever sees a proof.
     */
    var offer: ByteArray? = null
    var offerCode: String? = null

    /** "Only accept paired devices": turns a first contact into a signed refusal instead. */
    var requirePairing: Boolean = false

    /**
     * Answers even when the device asked for a different `studio_id`, which the real Studio refuses.
     * That is precisely the peer the pinned-key check exists for: something standing in the path, or
     * squatting the address a Mac used to have, has no reason to honour the id it was asked for.
     */
    var answersAnyIdentity: Boolean = false

    /** Set as each connection completes, so a test can assert what Studio believed happened. */
    var lastDeviceId: String? = null
    var lastFirstContact: Boolean = false
    var lastSessionCounter: Long = -1

    fun qrPayload(host: String, port: Int): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val secret = requireNotNull(offer) { "no pairing window is open" }
        return "wailo://pair?sid=$studioId&h=$host&p=$port" +
            "&k=${encoder.encodeToString(publicKey)}&s=${encoder.encodeToString(secret)}"
    }

    fun sign(nonceD: ByteArray, nonceS: ByteArray, ephemeralD: ByteArray, ephemeralS: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(WailoCrypto.transcript("wailo/studio", studioId, nonceD, nonceS, ephemeralD, ephemeralS))
        }.sign()
        return derToRaw(der)
    }

    /** CryptoKit hands out a bare `r || s`; the JCA signs into DER, so unwrap it the way Studio does. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var index = 2
        val r = readInteger(der, index).also { index += it.second }
        val s = readInteger(der, index)
        return r.first.toFixedWidth() + s.first.toFixedWidth()
    }

    private fun readInteger(der: ByteArray, at: Int): Pair<BigInteger, Int> {
        require(der[at] == 0x02.toByte()) { "expected a DER INTEGER" }
        val length = der[at + 1].toInt()
        return BigInteger(der.copyOfRange(at + 2, at + 2 + length)) to (2 + length)
    }

    private fun BigInteger.toFixedWidth(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}

/**
 * One connection's worth of Studio. Fed whole envelopes and answering with whole envelopes, so a test
 * can drive it over a real socket or straight in memory.
 */
internal class FakeStudioSession(private val studio: FakeStudio) {

    /** What to send back, the codec once the session is sealed, and whether to hang up. */
    class Reply(
        val out: List<Envelope> = emptyList(),
        val codec: WailoFrameCodec? = null,
        val close: Boolean = false,
    )

    private var nonceD: ByteArray? = null
    private var nonceS: ByteArray? = null
    private var ephemeralD: ByteArray? = null
    private var ephemeralS: ByteArray? = null
    private var shared: ByteArray? = null
    private var authKey: ByteArray? = null
    private var deviceKey: ByteArray? = null
    private var deviceId: String? = null
    private var firstContact = false

    fun handle(envelope: Envelope): Reply {
        envelope.auth_request?.let { return challenge(it) }
        envelope.auth_response?.let { return admit(it) }
        return Reply(close = true)
    }

    private fun challenge(request: AuthRequest): Reply {
        if (request.nonce.size != WailoCrypto.NONCE_LENGTH) return Reply(close = true)
        if (!studio.answersAnyIdentity &&
            request.studio_id.isNotEmpty() &&
            request.studio_id != studio.studioId
        ) {
            return Reply(close = true)
        }

        val nonceD = request.nonce.toByteArray()
        val nonceS = WailoCrypto.randomNonce()
        val ephemeralD = request.ephemeral_key.toByteArray()
        val ephemeral = WailoCrypto.generateEphemeral()
        val ephemeralS = WailoCrypto.encodePublicKey(ephemeral.public)
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralD) ?: return Reply(close = true)

        val offered = if (request.paired_by_code) {
            studio.offerCode?.let { WailoCrypto.stretch(it, studio.studioId) }
        } else {
            studio.offer
        }
        val invited = studio.known[request.device_id]
            ?: offered?.let { WailoCrypto.deviceKey(it, studio.studioId, request.device_id) }
        val firstContact = invited == null

        this.nonceD = nonceD
        this.nonceS = nonceS
        this.ephemeralD = ephemeralD
        this.ephemeralS = ephemeralS
        this.shared = shared
        this.deviceId = request.device_id
        this.firstContact = firstContact

        if (firstContact && studio.requirePairing) {
            // Signed but unmac'd: "I am the Studio you know, and I will not take you". The device may
            // only act on the refusal that follows because the signature came first.
            return Reply(
                out = listOf(
                    Envelope(
                        auth_challenge = AuthChallenge(
                            nonce = nonceS.toByteString(),
                            signature = studio.sign(nonceD, nonceS, ephemeralD, ephemeralS).toByteString(),
                            mac = ByteString.EMPTY,
                            public_key = studio.publicKey.toByteString(),
                            ephemeral_key = ephemeralS.toByteString(),
                        ),
                    ),
                    Envelope(
                        auth_result = AuthResult(
                            ok = false,
                            reason = "This Studio only accepts paired devices. Pair from its Devices panel.",
                        ),
                    ),
                ),
                close = true,
            )
        }

        deviceKey = invited
        val authKey = WailoCrypto.authKey(shared, invited)
        this.authKey = authKey
        return Reply(
            out = listOf(
                Envelope(
                    auth_challenge = AuthChallenge(
                        nonce = nonceS.toByteString(),
                        signature = studio.sign(nonceD, nonceS, ephemeralD, ephemeralS).toByteString(),
                        mac = WailoCrypto
                            .studioMac(authKey, studio.studioId, nonceD, nonceS, ephemeralD, ephemeralS)
                            .toByteString(),
                        public_key = studio.publicKey.toByteString(),
                        ephemeral_key = ephemeralS.toByteString(),
                    ),
                ),
            ),
        )
    }

    private fun admit(response: AuthResponse): Reply {
        val authKey = authKey ?: return Reply(close = true)
        val nonceD = nonceD!!
        val nonceS = nonceS!!
        val deviceId = deviceId!!
        val expected = WailoCrypto
            .deviceProof(authKey, studio.studioId, nonceD, nonceS, ephemeralD!!, ephemeralS!!)
        if (!WailoCrypto.constantTimeEquals(response.proof.toByteArray(), expected)) return Reply(close = true)

        studio.known[deviceId] = deviceKey
            ?: WailoCrypto.tofuDeviceKey(shared!!, studio.studioId, deviceId)
        studio.counters[deviceId] = response.session_counter
        studio.lastDeviceId = deviceId
        studio.lastFirstContact = firstContact
        studio.lastSessionCounter = response.session_counter
        // One displayed code pairs one device, rather than standing open for whoever else saw the screen.
        studio.offer = null
        studio.offerCode = null

        return Reply(
            out = listOf(Envelope(auth_result = AuthResult(ok = true))),
            codec = WailoFrameCodec(
                sessionKey = WailoCrypto.sessionKey(shared!!, deviceKey, nonceD, nonceS),
                sealing = WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
                opening = WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
            ),
        )
    }
}
