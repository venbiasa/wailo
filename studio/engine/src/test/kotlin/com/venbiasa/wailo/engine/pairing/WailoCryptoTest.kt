package com.venbiasa.wailo.engine.pairing

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same vectors as `sdk-ios/Tests/WailoSDKTests/WailoCryptoTests.swift`, asserted against this
 * build's independent implementation.
 *
 * The SDK build and the studio build are separate Gradle builds joined only by the `protocol`
 * artifact, so there is no shared code to keep these two in step — only these constants. A drift here
 * shows up in the field as devices that pair and then silently fail to authenticate, with nothing in
 * either log to say why.
 */
class WailoCryptoTest {

    private val pairingSecret = ByteArray(32) { it.toByte() }
    private val studioId = "0123456789abcdef0123456789abcdef"
    private val deviceId = "device-0001"
    private val nonceD = ByteArray(32) { (0x40 + it).toByte() }
    private val nonceS = ByteArray(32) { (0x60 + it).toByte() }

    private val deviceKey = WailoCrypto.deviceKey(pairingSecret, studioId, deviceId)

    // The agreement half of the vectors. The public points are the *expected* encodings of the two
    // fixed scalars, taken from the Swift suite: this build can agree from a scalar but has no way to
    // derive a point from one, so matching the shared secret below is what proves the two agree.
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
    fun `device key matches the iOS vector`() {
        assertEquals("0f5f735ac79ff7b5174adb12a2d175eeba55e307d27c4c30d52c879bef311c44", deviceKey.hex())
    }

    @Test
    fun `key agreement matches the iOS vector`() {
        assertEquals("4fe243908f378aa1c2a69538822e6ed908c3225d8692575507c649901245150a", shared.hex())
    }

    /**
     * A point off the curve must not agree to anything. That is the invalid-curve attack, and the JCA
     * will build a key from such a point quite happily if nobody checks.
     */
    @Test
    fun `an off-curve point is refused`() {
        val bogus = ephemeralS.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        assertNull(WailoCrypto.agree(privateKey(scalarD), bogus))
        assertNull(WailoCrypto.agree(privateKey(scalarD), ByteArray(0)))
    }

    @Test
    fun `tofu device key matches the iOS vector`() {
        assertEquals(
            "b504b4985c3055c9b4d07e9800b8d3234b802ba3dcf1fafea26fabd877cdd1df",
            WailoCrypto.tofuDeviceKey(shared, studioId, deviceId).hex(),
        )
    }

    @Test
    fun `auth key matches the iOS vector`() {
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
    fun `session key matches the iOS vector`() {
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
    fun `device proof matches the iOS vector`() {
        assertEquals(
            "e4556159809a931fd61e10aa8252efcabfdd116d465ee347ad5a5b697dbf34a9",
            WailoCrypto.deviceProof(
                WailoCrypto.authKey(shared, deviceKey), studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ).hex(),
        )
    }

    @Test
    fun `studio mac matches the iOS vector`() {
        assertEquals(
            "e369baf64bccd686dda614f3cf30ace730fb45944826f91419d9ede32b4d4199",
            WailoCrypto.studioMac(
                WailoCrypto.authKey(shared, deviceKey), studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ).hex(),
        )
    }

    /**
     * Swapping an ephemeral key must change what was signed over. Without this the signature says
     * nothing about the agreement, and anyone in the path can hold a separate one with each side.
     */
    @Test
    fun `the transcript covers the ephemeral keys`() {
        assertFalse(
            WailoCrypto.transcript("wailo/studio", studioId, nonceD, nonceS, ephemeralD, ephemeralS)
                .contentEquals(
                    WailoCrypto.transcript("wailo/studio", studioId, nonceD, nonceS, ephemeralD, ephemeralD),
                ),
        )
    }

    @Test
    fun `studio id matches the iOS vector`() {
        assertEquals("4bfd2c8b6f1eec7a2afeb48b934ee4b2", WailoCrypto.studioId(ByteArray(65) { it.toByte() }))
    }

    @Test
    fun `sealed frame matches the iOS vector`() {
        val codec = FrameCodec(
            sessionKey = ByteArray(32) { 0x2a },
            sealing = FrameCodec.Direction.DEVICE_TO_STUDIO,
            opening = FrameCodec.Direction.STUDIO_TO_DEVICE,
        )
        val sealed = codec.seal("wailo".toByteArray())
        assertEquals(0L, sealed.seq)
        assertEquals("421a8d16df530fe3dcd75e18d19e0485ab9184d7e6", sealed.ciphertext.hex())
    }

    @Test
    fun `a signature verifies against its own key`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val signature = manager.sign(nonceD, nonceS, ephemeralD, ephemeralS)
        assertTrue(
            WailoCrypto.verify(
                signature, manager.publicKey, manager.studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ),
        )
    }

    /** The property the Bonjour TXT record leans on: claiming an id you cannot sign for gets you nowhere. */
    @Test
    fun `a signature does not verify against another studio's key`() {
        val mine = PairingManager(InMemoryPairingKeyStore())
        val theirs = PairingManager(InMemoryPairingKeyStore())
        val signature = mine.sign(nonceD, nonceS, ephemeralD, ephemeralS)
        assertFalse(
            WailoCrypto.verify(
                signature, theirs.publicKey, mine.studioId, nonceD, nonceS, ephemeralD, ephemeralS,
            ),
        )
    }

    @Test
    fun `a signature does not verify over a different transcript`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val signature = manager.sign(nonceD, nonceS, ephemeralD, ephemeralS)
        assertFalse(
            WailoCrypto.verify(
                signature, manager.publicKey, manager.studioId, nonceS, nonceD, ephemeralD, ephemeralS,
            ),
        )
    }

    /** Substituting an ephemeral key is exactly what the signature exists to make detectable. */
    @Test
    fun `a signature does not verify against a swapped ephemeral key`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val signature = manager.sign(nonceD, nonceS, ephemeralD, ephemeralS)
        assertFalse(
            WailoCrypto.verify(
                signature, manager.publicKey, manager.studioId, nonceD, nonceS, ephemeralD, ephemeralD,
            ),
        )
    }

    /** Raw `r || s` is fixed-width, so a coordinate with a leading zero must not shift the bytes. */
    @Test
    fun `every signature is exactly 64 bytes`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        repeat(50) {
            assertEquals(
                64,
                manager.sign(
                    WailoCrypto.randomNonce(), WailoCrypto.randomNonce(), ephemeralD, ephemeralS,
                ).size,
            )
        }
    }

    @Test
    fun `public keys round-trip through the wire encoding`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val decoded = WailoCrypto.decodePublicKey(manager.publicKey)
        assertContentEquals(manager.publicKey, WailoCrypto.encodePublicKey(decoded))
    }

    @Test
    fun `frames round-trip between the two directions`() {
        val (device, studio) = pair()
        repeat(4) { index ->
            val payload = "frame-$index".toByteArray()
            val sealed = device.seal(payload)
            assertContentEquals(payload, studio.open(sealed.seq, sealed.ciphertext))
        }
    }

    @Test
    fun `a replayed frame is rejected`() {
        val (device, studio) = pair()
        val sealed = device.seal("once".toByteArray())
        studio.open(sealed.seq, sealed.ciphertext)
        assertFailsWith<FrameRejected> { studio.open(sealed.seq, sealed.ciphertext) }
    }

    @Test
    fun `an altered frame is rejected`() {
        val (device, studio) = pair()
        val sealed = device.seal("tamper me".toByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 1).toByte()
        assertFailsWith<FrameRejected> { studio.open(sealed.seq, sealed.ciphertext) }
    }

    /** `seq` travels in the clear, so moving it must break the open rather than silently decrypt. */
    @Test
    fun `an altered sequence is rejected`() {
        val (device, studio) = pair()
        device.seal("first".toByteArray())
        val sealed = device.seal("second".toByteArray())
        assertFailsWith<FrameRejected> { studio.open(sealed.seq + 5, sealed.ciphertext) }
    }

    @Test
    fun `the two directions never share a nonce`() {
        val key = ByteArray(32) { 0x2a }
        val device = FrameCodec(key, FrameCodec.Direction.DEVICE_TO_STUDIO, FrameCodec.Direction.STUDIO_TO_DEVICE)
        val studio = FrameCodec(key, FrameCodec.Direction.STUDIO_TO_DEVICE, FrameCodec.Direction.DEVICE_TO_STUDIO)
        assertNotEquals(
            device.seal("wailo".toByteArray()).ciphertext.hex(),
            studio.seal("wailo".toByteArray()).ciphertext.hex(),
        )
    }

    @Test
    fun `a typed code stretches deterministically and is salted by the studio`() {
        val first = WailoCrypto.stretch("ABCDEFGHJK", studioId)
        assertContentEquals(first, WailoCrypto.stretch("ABCDEFGHJK", studioId))
        assertFalse(first.contentEquals(WailoCrypto.stretch("ABCDEFGHJK", "a".repeat(32))))
    }

    private fun pair(): Pair<FrameCodec, FrameCodec> {
        val key = ByteArray(32) { 0x11 }
        return FrameCodec(key, FrameCodec.Direction.DEVICE_TO_STUDIO, FrameCodec.Direction.STUDIO_TO_DEVICE) to
            FrameCodec(key, FrameCodec.Direction.STUDIO_TO_DEVICE, FrameCodec.Direction.DEVICE_TO_STUDIO)
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
