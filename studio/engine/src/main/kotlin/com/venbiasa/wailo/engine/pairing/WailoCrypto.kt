package com.venbiasa.wailo.engine.pairing

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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Studio's half of the identity-first WiFi handshake (ADR-0060). Every layout here is fixed by
 * `sdk-ios/Sources/WailoSDK/WailoCrypto.swift`, which cannot be shared with — the SDK build and the
 * studio build are separate Gradle builds joined only by the `protocol` artifact. `WailoCryptoTest`
 * and `WailoCryptoTests.swift` assert the same vectors; that agreement is the only thing keeping the
 * two implementations interoperable.
 *
 * Keys cross the wire as X9.63 uncompressed points and signatures as raw `r || s`, not the JVM's
 * native SPKI/DER, because those are the forms CryptoKit exposes at iOS 13. The conversions live at
 * the bottom of this file and exist purely to meet that constraint.
 */
object WailoCrypto {

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

    fun generateIdentity(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(CURVE), random) }
            .generateKeyPair()

    fun randomNonce(): ByteArray = ByteArray(NONCE_LENGTH).also(random::nextBytes)

    fun randomSecret(): ByteArray = ByteArray(KEY_LENGTH).also(random::nextBytes)

    // MARK: identity

    /** The first 16 bytes of the X9.63 public key's SHA-256, lowercase hex. */
    fun studioId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16).toHex()

    // MARK: key agreement

    /** A fresh P-256 agreement pair, one per connection and discarded with it. */
    fun generateEphemeral(): KeyPair = generateIdentity()

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
    fun stretch(code: String, studioId: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), studioId.toByteArray(), PBKDF2_ROUNDS, KEY_LENGTH * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

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

    fun signStudioHelloV3(
        privateKey: PrivateKey,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): ByteArray = signRaw(
        privateKey,
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

    fun verifyStudioHelloV3(
        signature: ByteArray,
        publicKey: ByteArray,
        studioId: String,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
    ): Boolean = verifyRaw(
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

    fun signResultV3(
        privateKey: PrivateKey,
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
    ): ByteArray = signRaw(
        privateKey,
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

    fun verifyResultV3(
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
    ): Boolean = verifyRaw(
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
     * SPKI DER (what `getEncoded` returns) down to the 65-byte uncompressed point the wire carries.
     * Rebuilt from the affine coordinates rather than sliced out of the DER, so a leading zero in
     * either coordinate cannot silently shift the result.
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

    private fun signRaw(privateKey: PrivateKey, transcript: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey, random)
            update(transcript)
        }
        return derToRawSignature(signature.sign())
    }

    private fun verifyRaw(signature: ByteArray, publicKey: ByteArray, transcript: ByteArray): Boolean = runCatching {
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
     * ECDSA signatures are `SEQUENCE { INTEGER r, INTEGER s }` on the JVM and a bare `r || s` in
     * CryptoKit. Hand-parsed rather than pulled from a library because the studio build has no
     * BouncyCastle and this is the entire ASN.1 surface either side needs.
     */
    private fun derToRawSignature(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++] == 0x30.toByte()) { "not a DER SEQUENCE" }
        offset += lengthFieldSize(der, offset)
        val (r, afterR) = readInteger(der, offset)
        val (s, _) = readInteger(der, afterR)
        return r.toFixedWidth() + s.toFixedWidth()
    }

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

    private fun readInteger(der: ByteArray, start: Int): Pair<BigInteger, Int> {
        var offset = start
        require(der[offset++] == 0x02.toByte()) { "not a DER INTEGER" }
        val length = der[offset++].toInt() and 0xFF
        val value = BigInteger(1, der.copyOfRange(offset, offset + length))
        return value to offset + length
    }

    // A P-256 signature is far below 128 bytes, so the length is always the short form — but read it
    // properly anyway rather than assume, since a malformed input reaching here would misparse silently.
    private fun lengthFieldSize(der: ByteArray, offset: Int): Int {
        val first = der[offset].toInt() and 0xFF
        return if (first < 0x80) 1 else 1 + (first and 0x7F)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
