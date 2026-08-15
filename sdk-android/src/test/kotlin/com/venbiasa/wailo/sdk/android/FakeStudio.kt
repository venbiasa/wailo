package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthClientHelloV3
import com.venbiasa.wailo.protocol.AuthDeviceProofV3
import com.venbiasa.wailo.protocol.AuthModeV3
import com.venbiasa.wailo.protocol.AuthResultCodeV3
import com.venbiasa.wailo.protocol.AuthResultV3
import com.venbiasa.wailo.protocol.AuthStudioHelloV3
import com.venbiasa.wailo.protocol.Envelope
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.nio.ByteBuffer
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
    var forgeHelloSignature: Boolean = false
    var forgeResultSignature: Boolean = false
    var forgeResultProof: Boolean = false

    /** Set as each connection completes, so a test can assert what Studio believed happened. */
    var lastDeviceAlias: String? = null
    var lastFirstContact: Boolean = false
    var lastSessionCounter: Long = -1

    fun qrPayload(host: String, port: Int): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val secret = requireNotNull(offer) { "no pairing window is open" }
        return "wailo://pair?sid=$studioId&h=$host&p=$port" +
            "&k=${encoder.encodeToString(publicKey)}&s=${encoder.encodeToString(secret)}"
    }

    fun signHelloV3(
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): ByteArray = sign(
        helloTranscriptV3(
            "wailo/studio-hello/v3",
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
        ),
    )

    fun signResultV3(
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        counter: Long,
        code: Int,
    ): ByteArray = sign(
        helloTranscriptV3(
            "wailo/result-signature/v3",
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
        ) + label("wailo/device-alias/v3", deviceAlias.toByteArray()) +
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(mode).array() +
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array() +
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(code).array(),
    )

    private fun helloTranscriptV3(
        role: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): ByteArray = label(role, studioId.toByteArray()) +
        nonceD + nonceS + ephemeralD + ephemeralS + byteArrayOf(if (pairingRequired) 1 else 0)

    private fun label(text: String, suffix: ByteArray): ByteArray =
        text.toByteArray() + byteArrayOf(0) + suffix + byteArrayOf(0)

    private fun sign(transcript: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(transcript)
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

    fun handle(envelope: Envelope): Reply {
        envelope.auth_client_hello_v3?.let { return hello(it) }
        envelope.auth_device_proof_v3?.let { return admit(it) }
        return Reply(close = true)
    }

    private fun hello(request: AuthClientHelloV3): Reply {
        if (request.version != 3 || request.nonce.size != WailoCrypto.NONCE_LENGTH) return Reply(close = true)
        val nonceD = request.nonce.toByteArray()
        val nonceS = WailoCrypto.randomNonce()
        val ephemeralD = request.ephemeral_key.toByteArray()
        val ephemeral = WailoCrypto.generateEphemeral()
        val ephemeralS = WailoCrypto.encodePublicKey(ephemeral.public)
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralD) ?: return Reply(close = true)

        this.nonceD = nonceD
        this.nonceS = nonceS
        this.ephemeralD = ephemeralD
        this.ephemeralS = ephemeralS
        this.shared = shared
        return Reply(
            out = listOf(
                Envelope(
                    auth_studio_hello_v3 = AuthStudioHelloV3(
                        nonce = nonceS.toByteString(),
                        public_key = studio.publicKey.toByteString(),
                        ephemeral_key = ephemeralS.toByteString(),
                        signature = if (studio.forgeHelloSignature) {
                            ByteArray(64).toByteString()
                        } else {
                            studio.signHelloV3(
                                nonceD,
                                nonceS,
                                ephemeralD,
                                ephemeralS,
                                studio.requirePairing,
                            ).toByteString()
                        },
                        pairing_required = studio.requirePairing,
                    ),
                ),
            ),
        )
    }

    private fun admit(response: AuthDeviceProofV3): Reply {
        val nonceD = nonceD!!
        val nonceS = nonceS!!
        val ephemeralD = ephemeralD!!
        val ephemeralS = ephemeralS!!
        val shared = shared!!
        val alias = response.device_alias
        val known = studio.known[alias]
        val selected = when (response.mode) {
            AuthModeV3.AUTH_MODE_V3_KNOWN -> {
                known ?: return refusal(response, AuthResultCodeV3.AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE)
            }

            AuthModeV3.AUTH_MODE_V3_TOFU -> {
                if (studio.requirePairing) {
                    return refusal(response, AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_REQUIRED)
                }
                WailoCrypto.tofuDeviceKeyV3(shared, studio.studioId, alias)
            }

            AuthModeV3.AUTH_MODE_V3_INVITED_QR -> {
                val offer = studio.offer
                    ?: return refusal(response, AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID)
                WailoCrypto.deviceKeyV3(offer, studio.studioId, alias)
            }

            AuthModeV3.AUTH_MODE_V3_INVITED_CODE -> {
                val code = studio.offerCode
                    ?: return refusal(response, AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID)
                WailoCrypto.deviceKeyV3(WailoCrypto.stretch(code, studio.studioId), studio.studioId, alias)
            }

            AuthModeV3.AUTH_MODE_V3_UNSPECIFIED -> return Reply(close = true)
        }
        val authKey = WailoCrypto.authKeyV3(shared, selected)
        val expected = WailoCrypto
            .deviceProofV3(
                authKey,
                studio.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
                studio.requirePairing,
                alias,
                response.mode.value,
                response.session_counter,
            )
        if (!WailoCrypto.constantTimeEquals(response.proof.toByteArray(), expected)) return Reply(close = true)

        studio.known[alias] = selected
        studio.counters[alias] = response.session_counter
        studio.lastDeviceAlias = alias
        studio.lastFirstContact = response.mode == AuthModeV3.AUTH_MODE_V3_TOFU
        studio.lastSessionCounter = response.session_counter
        // One displayed code pairs one device, rather than standing open for whoever else saw the screen.
        studio.offer = null
        studio.offerCode = null

        val code = AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK
        return Reply(
            out = listOf(
                Envelope(
                    auth_result_v3 = AuthResultV3(
                        code = code,
                        proof = if (studio.forgeResultProof) {
                            ByteArray(32).toByteString()
                        } else {
                            WailoCrypto.studioProofV3(
                                authKey,
                                studio.studioId,
                                nonceD,
                                nonceS,
                                ephemeralD,
                                ephemeralS,
                                studio.requirePairing,
                                alias,
                                response.mode.value,
                                response.session_counter,
                                code.value,
                            ).toByteString()
                        },
                        signature = if (studio.forgeResultSignature) {
                            ByteArray(64).toByteString()
                        } else {
                            studio.signResultV3(
                                nonceD,
                                nonceS,
                                ephemeralD,
                                ephemeralS,
                                studio.requirePairing,
                                alias,
                                response.mode.value,
                                response.session_counter,
                                code.value,
                            ).toByteString()
                        },
                    ),
                ),
            ),
            codec = WailoFrameCodec(
                sessionKey = WailoCrypto.sessionKeyV3(shared, selected, nonceD, nonceS),
                sealing = WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
                opening = WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
            ),
        )
    }

    private fun refusal(response: AuthDeviceProofV3, code: AuthResultCodeV3): Reply {
        val nonceD = nonceD!!
        val nonceS = nonceS!!
        val ephemeralD = ephemeralD!!
        val ephemeralS = ephemeralS!!
        return Reply(
            out = listOf(
                Envelope(
                    auth_result_v3 = AuthResultV3(
                        code = code,
                        proof = ByteString.EMPTY,
                        signature = studio.signResultV3(
                            nonceD,
                            nonceS,
                            ephemeralD,
                            ephemeralS,
                            studio.requirePairing,
                            response.device_alias,
                            response.mode.value,
                            response.session_counter,
                            code.value,
                        ).toByteString(),
                    ),
                ),
            ),
            close = true,
        )
    }
}
