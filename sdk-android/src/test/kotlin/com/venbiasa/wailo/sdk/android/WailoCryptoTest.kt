package com.venbiasa.wailo.sdk.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The same vectors as `engine/src/test/.../WailoCryptoTest.kt` and
 * `sdk-ios/Tests/WailoSDKTests/WailoCryptoTests.swift`, asserted against this build's independent
 * implementation.
 *
 * Three implementations of one byte-for-byte contract, with no shared code possible between any two of
 * them: `sdk-ios` is Swift, and the studio build is a separate Gradle build joined only by the
 * `protocol` artifact (ADR-0015). These constants are the only thing keeping them in step. Drift shows
 * up in the field as a device that pairs and then silently fails to authenticate, with nothing in any
 * log to say why.
 */
class WailoCryptoTest {

    private val pairingSecret = ByteArray(32) { it.toByte() }
    private val studioId = "0123456789abcdef0123456789abcdef"
    private val deviceId = "device-0001"
    private val nonceD = ByteArray(32) { (0x40 + it).toByte() }
    private val nonceS = ByteArray(32) { (0x60 + it).toByte() }

    private val deviceKey = WailoCrypto.deviceKey(pairingSecret, studioId, deviceId)

    private val scalarD = ByteArray(32) { (it + 1).toByte() }
    private val ephemeralD = (
        "04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b403" +
            "5f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354"
        ).bytes()
    private val ephemeralS = (
        "041f140146bfb1b251f84f4ddbe0d4cdcfd77afd984a9520e35794021f8312bb" +
            "9eec995a08b1fa7704df3dcc0b50a9665263fb7711f95f9f8a449c5096e47c892b"
        ).bytes()
    private val shared by lazy { WailoCrypto.agree(privateKey(scalarD), ephemeralS)!! }

    @Test
    fun deviceKeyMatchesTheSharedVector() {
        assertEquals("0f5f735ac79ff7b5174adb12a2d175eeba55e307d27c4c30d52c879bef311c44", deviceKey.hex())
    }

    @Test
    fun keyAgreementMatchesTheSharedVector() {
        assertEquals("4fe243908f378aa1c2a69538822e6ed908c3225d8692575507c649901245150a", shared.hex())
    }

    /**
     * A point off the curve must not agree to anything. That is the invalid-curve attack, and the JCA
     * will build a key from such a point quite happily if nobody checks.
     */
    @Test
    fun anOffCurvePointIsRefused() {
        val bogus = ephemeralS.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        assertNull(WailoCrypto.agree(privateKey(scalarD), bogus))
        assertNull(WailoCrypto.agree(privateKey(scalarD), ByteArray(0)))
    }

    @Test
    fun tofuDeviceKeyMatchesTheSharedVector() {
        assertEquals(
            "b504b4985c3055c9b4d07e9800b8d3234b802ba3dcf1fafea26fabd877cdd1df",
            WailoCrypto.tofuDeviceKey(shared, studioId, deviceId).hex(),
        )
    }

    @Test
    fun authKeyMatchesTheSharedVector() {
        assertEquals(
            "37cfb80bf35ea202e79894afc5babc35aac2d604e6eea05deffe0ea50473a834",
            WailoCrypto.authKey(shared, null).hex(),
        )
        assertEquals(
            "28c5cb8ca5bcc0ab7327bf87088f4e756578abb3e14fdda32d9e4bb509c5153b",
            WailoCrypto.authKey(shared, deviceKey).hex(),
        )
    }

    @Test
    fun sessionKeyMatchesTheSharedVector() {
        assertEquals(
            "feebb90e8f30121c043444b0eb3c4a907d9622a8c3144e532311cf44e289a403",
            WailoCrypto.sessionKey(shared, null, nonceD, nonceS).hex(),
        )
        assertEquals(
            "895b7888dbd5f197933cd84537169fa3e6aa2aa140660d489054ba6cd3d8c69b",
            WailoCrypto.sessionKey(shared, deviceKey, nonceD, nonceS).hex(),
        )
    }

    @Test
    fun deviceProofMatchesTheSharedVector() {
        assertEquals(
            "e4556159809a931fd61e10aa8252efcabfdd116d465ee347ad5a5b697dbf34a9",
            WailoCrypto.deviceProof(
                WailoCrypto.authKey(shared, deviceKey), studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ).hex(),
        )
    }

    @Test
    fun studioMacMatchesTheSharedVector() {
        assertEquals(
            "e369baf64bccd686dda614f3cf30ace730fb45944826f91419d9ede32b4d4199",
            WailoCrypto.studioMac(
                WailoCrypto.authKey(shared, deviceKey), studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ).hex(),
        )
    }

    @Test
    fun studioIdMatchesTheSharedVector() {
        assertEquals("4bfd2c8b6f1eec7a2afeb48b934ee4b2", WailoCrypto.studioId(ByteArray(65) { it.toByte() }))
    }

    @Test
    fun sealedFrameMatchesTheSharedVector() {
        val codec = WailoFrameCodec(
            sessionKey = ByteArray(32) { 0x2a },
            sealing = WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
            opening = WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
        )
        val sealed = codec.seal("wailo".toByteArray())
        assertEquals(0L, sealed.seq)
        assertEquals("421a8d16df530fe3dcd75e18d19e0485ab9184d7e6", sealed.ciphertext.hex())
    }

    /**
     * The hand-rolled PBKDF2 against the JVM's own. This is the one primitive that could not be taken
     * from the platform — `SecretKeyFactory` only offers `PBKDF2WithHmacSHA256` from API 26 against an
     * SDK floor of 24 — so it is the one that most needs an independent witness. The test JVM has the
     * real thing; Android below 26 does not.
     */
    @Test
    fun stretchMatchesTheJvmsOwnPbkdf2() {
        val code = "ABCDEFGHJK"
        val reference = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), studioId.toByteArray(), 200_000, 256))
            .encoded
        assertArrayEquals(reference, WailoCrypto.stretch(code, studioId))
    }

    @Test
    fun aTypedCodeStretchesDeterministicallyAndIsSaltedByTheStudio() {
        val first = WailoCrypto.stretch("ABCDEFGHJK", studioId)
        assertArrayEquals(first, WailoCrypto.stretch("ABCDEFGHJK", studioId))
        assertFalse(first.contentEquals(WailoCrypto.stretch("ABCDEFGHJK", "a".repeat(32))))
    }

    /**
     * Swapping an ephemeral key must change what was signed over. Without this the signature says
     * nothing about the agreement, and anyone in the path can hold a separate one with each side.
     */
    @Test
    fun theTranscriptCoversTheEphemeralKeys() {
        assertFalse(
            WailoCrypto.transcript("wailo/studio", studioId, nonceD, nonceS, ephemeralD, ephemeralS)
                .contentEquals(
                    WailoCrypto.transcript("wailo/studio", studioId, nonceD, nonceS, ephemeralD, ephemeralD),
                ),
        )
    }

    @Test
    fun aSignatureVerifiesAgainstItsOwnKey() {
        val studio = FakeStudio()
        assertTrue(
            WailoCrypto.isValidStudioSignature(
                studio.sign(nonceD, nonceS, ephemeralD, ephemeralS),
                studio.publicKey,
                studio.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
            ),
        )
    }

    /** The property the Bonjour TXT record leans on: claiming an id you cannot sign for gets you nowhere. */
    @Test
    fun aSignatureDoesNotVerifyAgainstAnotherStudiosKey() {
        val mine = FakeStudio()
        val theirs = FakeStudio()
        assertFalse(
            WailoCrypto.isValidStudioSignature(
                mine.sign(nonceD, nonceS, ephemeralD, ephemeralS),
                theirs.publicKey,
                mine.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
            ),
        )
    }

    /** Substituting an ephemeral key is exactly what the signature exists to make detectable. */
    @Test
    fun aSignatureDoesNotVerifyAgainstASwappedEphemeralKey() {
        val studio = FakeStudio()
        assertFalse(
            WailoCrypto.isValidStudioSignature(
                studio.sign(nonceD, nonceS, ephemeralD, ephemeralS),
                studio.publicKey,
                studio.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralD,
            ),
        )
    }

    /** Raw `r || s` is fixed-width, so a coordinate with a leading zero must not shift the bytes. */
    @Test
    fun everySignatureIsExactlySixtyFourBytes() {
        val studio = FakeStudio()
        repeat(50) {
            assertEquals(
                64,
                studio.sign(WailoCrypto.randomNonce(), WailoCrypto.randomNonce(), ephemeralD, ephemeralS).size,
            )
        }
    }

    @Test
    fun publicKeysRoundTripThroughTheWireEncoding() {
        val studio = FakeStudio()
        assertArrayEquals(
            studio.publicKey,
            WailoCrypto.encodePublicKey(WailoCrypto.decodePublicKey(studio.publicKey)),
        )
    }

    @Test
    fun framesRoundTripBetweenTheTwoDirections() {
        val (device, studio) = codecPair()
        repeat(4) { index ->
            val payload = "frame-$index".toByteArray()
            val sealed = device.seal(payload)
            assertArrayEquals(payload, studio.open(sealed.seq, sealed.ciphertext))
        }
    }

    @Test(expected = FrameRejected::class)
    fun aReplayedFrameIsRejected() {
        val (device, studio) = codecPair()
        val sealed = device.seal("once".toByteArray())
        studio.open(sealed.seq, sealed.ciphertext)
        studio.open(sealed.seq, sealed.ciphertext)
    }

    @Test(expected = FrameRejected::class)
    fun anAlteredFrameIsRejected() {
        val (device, studio) = codecPair()
        val sealed = device.seal("tamper me".toByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 1).toByte()
        studio.open(sealed.seq, sealed.ciphertext)
    }

    /** `seq` travels in the clear, so moving it must break the open rather than silently decrypt. */
    @Test(expected = FrameRejected::class)
    fun anAlteredSequenceIsRejected() {
        val (device, studio) = codecPair()
        device.seal("first".toByteArray())
        val sealed = device.seal("second".toByteArray())
        studio.open(sealed.seq + 5, sealed.ciphertext)
    }

    @Test
    fun theTwoDirectionsNeverShareANonce() {
        val key = ByteArray(32) { 0x2a }
        val device = WailoFrameCodec(
            key, WailoFrameCodec.Direction.DEVICE_TO_STUDIO, WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
        )
        val studio = WailoFrameCodec(
            key, WailoFrameCodec.Direction.STUDIO_TO_DEVICE, WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
        )
        assertNotEquals(
            device.seal("wailo".toByteArray()).ciphertext.hex(),
            studio.seal("wailo".toByteArray()).ciphertext.hex(),
        )
    }

    private fun codecPair(): Pair<WailoFrameCodec, WailoFrameCodec> {
        val key = ByteArray(32) { 0x11 }
        return WailoFrameCodec(
            key, WailoFrameCodec.Direction.DEVICE_TO_STUDIO, WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
        ) to WailoFrameCodec(
            key, WailoFrameCodec.Direction.STUDIO_TO_DEVICE, WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Rebuilds a fixed scalar as a P-256 private key, so the vectors above are reproducible. */
    private fun privateKey(scalar: ByteArray): PrivateKey {
        val parameters = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), parameters))
    }
}
