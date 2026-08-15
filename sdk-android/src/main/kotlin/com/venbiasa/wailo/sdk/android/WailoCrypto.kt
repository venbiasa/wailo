package com.venbiasa.wailo.sdk.android

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The device's half of the identity-first WiFi handshake primitives (ADR-0060), the third independent
 * implementation of one byte-for-byte contract: `sdk-ios/Sources/WailoSDK/WailoCrypto.swift` and
 * `engine/pairing/WailoCrypto.kt`. Nothing can be shared with either — `sdk-ios` is Swift, and the
 * studio build is a separate Gradle build joined only by the `protocol` artifact (ADR-0015) — so every
 * label, constant and concatenation order here is a contract, and `WailoCryptoTest` asserts the same
 * vectors all three suites do. Drift shows up in the field as a device that pairs and then silently
 * fails to authenticate, with nothing in any log to say why.
 *
 * Only the device's half is here. Studio signs and the device verifies, so there is no `sign` and no
 * DER-encoder — the conversion runs one way, raw `r || s` to DER, purely to feed `SHA256withECDSA`.
 */
internal object WailoCrypto {

    const val NONCE_LENGTH = 32
    const val KEY_LENGTH = 32

    private const val CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val HMAC = "HmacSHA256"
    private const val COORDINATE_LENGTH = 32
    private const val PBKDF2_ROUNDS = 200_000

    private val random = SecureRandom()
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    fun randomNonce(): ByteArray = ByteArray(NONCE_LENGTH).also(random::nextBytes)

    fun randomSecret(): ByteArray = ByteArray(KEY_LENGTH).also(random::nextBytes)

    // MARK: identity

    /** The first 16 bytes of the X9.63 public key's SHA-256, lowercase hex. */
    fun studioId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16).toHex()

    // MARK: key agreement

    /** A fresh P-256 agreement pair, one per connection and discarded with it. */
    fun generateEphemeral(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(CURVE), random) }
            .generateKeyPair()

    /**
     * The agreed secret: the X coordinate of the shared point, which is what the JVM's `ECDH` and
     * CryptoKit both return. Null for a peer key that is malformed or off the curve.
     */
    fun agree(privateKey: PrivateKey, peer: ByteArray): ByteArray? = runCatching {
        KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(decodePublicKey(peer), true)
        }.generateSecret()
    }.getOrNull()

    // MARK: key schedule

    fun deviceKeyV3(pairingSecret: ByteArray, studioId: String, deviceAlias: String): ByteArray =
        hkdf(pairingSecret, studioId.toByteArray(), label("wailo/device-key/v3", deviceAlias.toByteArray()))

    fun tofuDeviceKeyV3(shared: ByteArray, studioId: String, deviceAlias: String): ByteArray =
        hkdf(shared, studioId.toByteArray(), label("wailo/tofu-device-key/v3", deviceAlias.toByteArray()))

    fun authKeyV3(shared: ByteArray, deviceKey: ByteArray): ByteArray =
        hkdf(shared + deviceKey, ByteArray(0), "wailo/auth/v3".toByteArray())

    fun sessionKeyV3(
        shared: ByteArray,
        deviceKey: ByteArray,
        nonceD: ByteArray,
        nonceS: ByteArray,
    ): ByteArray = hkdf(shared + deviceKey, nonceD + nonceS, "wailo/session/v3".toByteArray())

    /**
     * Stretches a typed pairing code into the same 32 bytes a scanned QR carries directly. The
     * iteration count is what makes a code short enough to type survive an offline guess.
     */
    fun stretch(code: String, studioId: String): ByteArray =
        pbkdf2(code.toByteArray(), studioId.toByteArray(), PBKDF2_ROUNDS)

    // MARK: proofs

    fun deviceProofV3(
        authKey: ByteArray,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
    ): ByteArray = hmac(
        authKey,
        authTranscriptV3(
            "wailo/device-proof/v3",
            studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
            deviceAlias,
            mode,
            sessionCounter,
        ),
    )

    fun studioProofV3(
        authKey: ByteArray,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
        resultCode: Int,
    ): ByteArray = hmac(
        authKey,
        resultTranscriptV3(
            "wailo/studio-proof/v3",
            studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
            deviceAlias,
            mode,
            sessionCounter,
            resultCode,
        ),
    )

    fun isValidStudioHelloV3(
        signature: ByteArray,
        publicKey: ByteArray,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): Boolean = verifyV3(
        signature,
        publicKey,
        helloTranscriptV3(
            "wailo/studio-hello/v3",
            studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
        ),
    )

    fun isValidResultSignatureV3(
        signature: ByteArray,
        publicKey: ByteArray,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
        resultCode: Int,
    ): Boolean = verifyV3(
        signature,
        publicKey,
        resultTranscriptV3(
            "wailo/result-signature/v3",
            studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
            deviceAlias,
            mode,
            sessionCounter,
            resultCode,
        ),
    )

    /** Constant-time, because these compare secrets. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }

    // MARK: key encoding

    /**
     * Down to the 65-byte uncompressed point the wire carries. Rebuilt from the affine coordinates
     * rather than sliced out of the DER, so a leading zero in either coordinate cannot shift the result.
     */
    fun encodePublicKey(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(0x04) + point.affineX.toFixedWidth() + point.affineY.toFixedWidth()
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        require(encoded.size == 1 + 2 * COORDINATE_LENGTH && encoded[0] == 0x04.toByte()) {
            "expected an X9.63 uncompressed P-256 point"
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 1 + COORDINATE_LENGTH))
        val y = BigInteger(1, encoded.copyOfRange(1 + COORDINATE_LENGTH, encoded.size))
        val parameters = curveParameters()
        // The JCA will happily build a key from a point that is not on the curve, and this key then
        // goes into ECDH — the setup for an invalid-curve attack, where an attacker feeds points from
        // weak curves and reads the private scalar out of the answers. CryptoKit rejects these on the
        // other side; match it here rather than rely on the provider.
        require(isOnCurve(x, y, parameters)) { "point is not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger, parameters: ECParameterSpec): Boolean {
        val curve = parameters.curve
        val p = (curve.field as? ECFieldFp)?.p ?: return false
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.modPow(TWO, p)
        val right = (x.modPow(THREE, p) + curve.a * x + curve.b).mod(p)
        return left == right
    }

    private fun curveParameters(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec(CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)

    // MARK: internals

    /** A `0x00`-separated label, so a transcript can never be read two ways. */
    private fun label(text: String, suffix: ByteArray): ByteArray =
        text.toByteArray() + byteArrayOf(0) + suffix + byteArrayOf(0)

    private fun helloTranscriptV3(
        role: String,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): ByteArray = label(role, studioId.toByteArray()) +
        nonceD + nonceS + ephemeralD + ephemeralS + byteArrayOf(if (pairingRequired) 1 else 0)

    private fun authTranscriptV3(
        role: String,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
    ): ByteArray = helloTranscriptV3(
        role,
        studioId,
        nonceD,
        nonceS,
        ephemeralD,
        ephemeralS,
        pairingRequired,
    ) + label("wailo/device-alias/v3", deviceAlias.toByteArray()) +
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(mode).array() +
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sessionCounter).array()

    private fun resultTranscriptV3(
        role: String,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        deviceAlias: String,
        mode: Int,
        sessionCounter: Long,
        resultCode: Int,
    ): ByteArray = authTranscriptV3(
        role,
        studioId,
        nonceD,
        nonceS,
        ephemeralD,
        ephemeralS,
        pairingRequired,
        deviceAlias,
        mode,
        sessionCounter,
    ) + ByteBuffer.allocate(Int.SIZE_BYTES).putInt(resultCode).array()

    private fun verifyV3(signature: ByteArray, publicKey: ByteArray, transcript: ByteArray): Boolean = runCatching {
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(decodePublicKey(publicKey))
            update(transcript)
        }.verify(rawToDerSignature(signature))
    }.getOrDefault(false)

    /** RFC 5869 HKDF-SHA256. One SHA-256 block out, so expand is a single iteration. */
    private fun hkdf(keyMaterial: ByteArray, salt: ByteArray, info: ByteArray): ByteArray =
        hmac(hmac(salt, keyMaterial), info + byteArrayOf(1))

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply {
            // An all-zero key is legal HMAC input but `SecretKeySpec` rejects an *empty* one, which is
            // what HKDF-extract passes when there is no salt.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, HMAC))
        }.doFinal(message)

    /**
     * RFC 2898 PBKDF2-HMAC-SHA256, hand-composed — and the one place this file has to diverge from the
     * other two implementations, which both call their platform's own.
     *
     * `SecretKeyFactory` only offers `PBKDF2WithHmacSHA256` from Android API 26 against an SDK floor of
     * 24, and vendors are known to strip it even above that. Dropping to `PBKDF2WithHmacSHA1` would be
     * a different function and would silently stop this device pairing with Studio or iOS, and pulling
     * in Bouncy Castle for one KDF is exactly the weight invariant #3 forbids. `Mac` is on every level.
     *
     * The output is 32 bytes, which is one HMAC-SHA256 block, so there is a single `T_1` and no block
     * loop — `WailoCryptoTest` pins this against the JCA's own implementation on the test JVM.
     */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
        var block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = block.copyOf()
        repeat(rounds - 1) {
            block = mac.doFinal(block)
            for (index in result.indices) result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
        }
        return result
    }

    private fun BigInteger.toFixedWidth(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == COORDINATE_LENGTH -> bytes
            // BigInteger prepends a sign byte whenever the high bit is set.
            bytes.size > COORDINATE_LENGTH -> bytes.copyOfRange(bytes.size - COORDINATE_LENGTH, bytes.size)
            else -> ByteArray(COORDINATE_LENGTH - bytes.size) + bytes
        }
    }

    /**
     * CryptoKit hands out a bare `r || s`; `SHA256withECDSA` wants `SEQUENCE { INTEGER r, INTEGER s }`.
     * Hand-rolled because this is the entire ASN.1 surface the device needs and the SDK ships inside
     * third-party apps (invariant #3).
     */
    private fun rawToDerSignature(raw: ByteArray): ByteArray {
        require(raw.size == 2 * COORDINATE_LENGTH) { "expected a 64-byte r||s signature" }
        val r = derInteger(BigInteger(1, raw.copyOf(COORDINATE_LENGTH)))
        val s = derInteger(BigInteger(1, raw.copyOfRange(COORDINATE_LENGTH, raw.size)))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(value: BigInteger): ByteArray {
        // BigInteger.toByteArray is already minimal two's complement, which is exactly DER INTEGER.
        val bytes = value.toByteArray()
        return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
