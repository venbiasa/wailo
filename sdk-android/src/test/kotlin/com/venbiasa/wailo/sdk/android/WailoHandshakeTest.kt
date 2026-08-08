package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthChallenge
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.RuleAck
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The device's half of the WiFi handshake, driven against [FakeStudio] — a copy of Studio's half, since
 * the engine is in a separate build this one must not depend on.
 *
 * The reconnect case is the one that earns this file. On iOS the trust was captured when the client was
 * built and never re-read, so every reconnect after a trust-on-first-use pairing re-introduced the
 * device as a stranger and failed the mac check, silently and forever (ADR-0046). It survived because
 * the only peer a unit test can dial is loopback, which skips the handshake entirely.
 */
class WailoHandshakeTest {

    private companion object {
        const val DEVICE_ID = "device-0001"
        const val HOST = "10.0.0.2"
    }

    @Before
    fun setUp() {
        WailoPairingStore.storage = InMemoryKeyValueStore()
        WailoHostStore.storage = InMemoryKeyValueStore()
    }

    @Test
    fun aFirstContactPairsAndBothEndsAgreeOnTheSessionKey() {
        val studio = FakeStudio()
        val exchange = handshake(studio, WailoHandshake.Trust.FirstContact)

        val done = exchange.step as WailoHandshake.Step.Done
        assertEquals(studio.studioId, done.established.pairing.studioId)
        assertTrue(done.established.pairing.trustedOnFirstUse)
        assertEquals(HOST, done.established.pairing.lastHost)
        assertTrue(studio.lastFirstContact)
        assertEquals(DEVICE_ID, studio.lastDeviceId)
        assertSameSessionKey(done.established.sessionKey, exchange.studioCodec)
    }

    /**
     * The ADR-0046 regression, expressed as the thing that has to keep working: a device that has just
     * been trusted on first use comes back, and Studio — which now holds a key for it — demands proof.
     */
    @Test
    fun aReconnectAfterAFirstContactAuthenticatesAsPaired() {
        val studio = FakeStudio()
        val first = handshake(studio, WailoHandshake.Trust.FirstContact).step as WailoHandshake.Step.Done
        WailoPairingStore.save(first.established.pairing)

        val trust = WailoHandshake.trustFor(
            pairing = WailoEndpointResolver.pairingFor(HOST, studio.studioId),
            invite = null,
            deviceId = DEVICE_ID,
        )
        assertTrue(trust is WailoHandshake.Trust.Paired)

        val second = handshake(studio, trust)
        val done = second.step as WailoHandshake.Step.Done
        assertFalse(studio.lastFirstContact)
        assertFalse(done.established.pairing.trustedOnFirstUse)
        // The counter has to move, or Studio reads two devices sharing one key.
        assertEquals(2L, done.established.pairing.sessionCounter)
        assertEquals(2L, studio.lastSessionCounter)
        assertSameSessionKey(done.established.sessionKey, second.studioCodec)
    }

    @Test
    fun aScannedQrAuthenticatesOnTheFirstConnection() {
        val studio = FakeStudio().apply { offer = WailoCrypto.randomSecret() }
        val invite = WailoPairingInvite.fromQr(studio.qrPayload(HOST, 8899))
        assertNotNull(invite)

        val trust = WailoHandshake.trustFor(pairing = null, invite = invite, deviceId = DEVICE_ID)
        val done = handshake(studio, trust).step as WailoHandshake.Step.Done
        // Nothing was trusted on faith: the QR carried both the key and the secret.
        assertFalse(done.established.pairing.trustedOnFirstUse)
        assertFalse(studio.lastFirstContact)
        assertArrayEquals(studio.publicKey, done.established.pairing.publicKey)
    }

    @Test
    fun aTypedCodeAuthenticatesOnTheFirstConnection() {
        val code = "ABCDEFGHJK"
        val studio = FakeStudio().apply { offerCode = code }
        val invite = WailoPairingInvite.fromCode(code, studio.studioId, HOST, 8899)
        assertNotNull(invite)

        val done = handshake(
            studio,
            WailoHandshake.trustFor(pairing = null, invite = invite, deviceId = DEVICE_ID),
        ).step as WailoHandshake.Step.Done
        assertFalse(studio.lastFirstContact)
        // A code cannot carry a key, so the one the challenge offered is pinned — safe only because
        // the mac proved knowledge of the same code first.
        assertArrayEquals(studio.publicKey, done.established.pairing.publicKey)
    }

    /**
     * A remembered address answered by someone else. Never resolved down here: that decision is the
     * entire value of having pinned the key (ADR-0040).
     *
     * The impostor answers under an id it was not asked for, which a real Studio never does — and that
     * is the point. A peer standing in the path, or squatting the address a Mac used to have, has no
     * reason to honour the `studio_id` in the request, so the pinned key is the only thing that catches
     * it.
     */
    @Test
    fun aChangedIdentityAtAPinnedAddressStopsTheHandshake() {
        val original = FakeStudio()
        val paired = handshake(original, WailoHandshake.Trust.FirstContact).step as WailoHandshake.Step.Done
        WailoPairingStore.save(paired.established.pairing)

        val impostor = FakeStudio().apply { answersAnyIdentity = true }
        val step = handshake(
            impostor,
            WailoHandshake.trustFor(
                pairing = WailoEndpointResolver.pairingFor(HOST, null),
                invite = null,
                deviceId = DEVICE_ID,
            ),
        ).step

        val changed = step as WailoHandshake.Step.IdentityChanged
        assertEquals(original.studioId, changed.expected)
        assertEquals(impostor.studioId, changed.actual)
    }

    @Test
    fun aStudioThatOnlyTakesPairedDevicesRefusesWithAReason() {
        val studio = FakeStudio().apply { requirePairing = true }
        val refused = handshake(studio, WailoHandshake.Trust.FirstContact).step as WailoHandshake.Step.Refused
        assertTrue(refused.reason.isNotEmpty())
    }

    /**
     * The refusal is only worth acting on because a valid signature came first. Without that, anyone on
     * the network could park the device in a re-pair prompt by answering with `ok = false`.
     */
    @Test
    fun aRefusalFromAPeerThatCannotSignIsNotBelieved() {
        val studio = FakeStudio()
        val device = WailoHandshake(WailoHandshake.Trust.FirstContact, DEVICE_ID, HOST)
        device.begin()

        val forged = Envelope(
            auth_challenge = AuthChallenge(
                nonce = WailoCrypto.randomNonce().toByteString(),
                signature = ByteArray(64).toByteString(),
                public_key = studio.publicKey.toByteString(),
                ephemeral_key = WailoCrypto.encodePublicKey(WailoCrypto.generateEphemeral().public).toByteString(),
            ),
        )
        assertEquals(WailoHandshake.Step.Failed, device.handle(forged))
    }

    /** Anything that is not an auth frame, before auth completes, is a peer that skipped the handshake. */
    @Test
    fun aPeerThatSkipsTheHandshakeIsHungUpOn() {
        val device = WailoHandshake(WailoHandshake.Trust.FirstContact, DEVICE_ID, HOST)
        device.begin()
        assertEquals(WailoHandshake.Step.Failed, device.handle(Envelope(rule_ack = RuleAck(epoch = 1))))
    }

    // MARK: - driver

    private class Exchange(val step: WailoHandshake.Step, val studioCodec: WailoFrameCodec?)

    /** Runs both halves to a terminal step, passing envelopes straight across in memory. */
    private fun handshake(studio: FakeStudio, trust: WailoHandshake.Trust): Exchange {
        val session = FakeStudioSession(studio)
        val device = WailoHandshake(trust, DEVICE_ID, HOST)
        val inbound = ArrayDeque(session.handle(device.begin()).out)
        var codec: WailoFrameCodec? = null
        var last: WailoHandshake.Step = WailoHandshake.Step.Ignore

        while (inbound.isNotEmpty()) {
            last = device.handle(inbound.removeFirst())
            when (last) {
                is WailoHandshake.Step.Send -> {
                    val reply = session.handle((last as WailoHandshake.Step.Send).envelope)
                    reply.codec?.let { codec = it }
                    inbound.addAll(reply.out)
                }

                WailoHandshake.Step.Ignore -> Unit
                else -> return Exchange(last, codec)
            }
        }
        return Exchange(last, codec)
    }

    /** The only proof that matters: what one side sealed, the other opens. */
    private fun assertSameSessionKey(deviceKey: ByteArray, studioCodec: WailoFrameCodec?) {
        val device = WailoFrameCodec(
            deviceKey,
            WailoFrameCodec.Direction.DEVICE_TO_STUDIO,
            WailoFrameCodec.Direction.STUDIO_TO_DEVICE,
        )
        val sealed = device.seal("wailo".toByteArray())
        assertArrayEquals(
            "wailo".toByteArray(),
            requireNotNull(studioCodec).open(sealed.seq, sealed.ciphertext),
        )
    }
}
