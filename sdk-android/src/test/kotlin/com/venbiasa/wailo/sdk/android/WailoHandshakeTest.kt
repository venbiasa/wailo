package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthStudioHelloV3
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
 * device as a stranger and failed authentication, silently and forever (ADR-0046). It survived because
 * the only peer a unit test can dial is loopback, which skips the handshake entirely.
 */
class WailoHandshakeTest {

    private companion object {
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
        val exchange = handshake(studio, WailoHandshake.Trust.FirstContact(null))

        val done = exchange.step as WailoHandshake.Step.Done
        assertEquals(studio.studioId, done.established.pairing.studioId)
        assertTrue(done.established.pairing.trustedOnFirstUse)
        assertEquals(HOST, done.established.pairing.lastHost)
        assertTrue(studio.lastFirstContact)
        assertEquals(done.established.pairing.deviceAlias, studio.lastDeviceAlias)
        assertEquals(32, done.established.pairing.deviceAlias.length)
        assertSameSessionKey(done.established.sessionKey, exchange.studioCodec)
    }

    /**
     * The ADR-0046 regression, expressed as the thing that has to keep working: a device that has just
     * been trusted on first use comes back, and Studio — which now holds a key for it — demands proof.
     */
    @Test
    fun aReconnectAfterAFirstContactAuthenticatesAsPaired() {
        val studio = FakeStudio()
        val first = handshake(studio, WailoHandshake.Trust.FirstContact(null)).step as WailoHandshake.Step.Done
        WailoPairingStore.save(first.established.pairing)

        val trust = WailoHandshake.trustFor(
            pairing = WailoEndpointResolver.pairingFor(HOST, studio.studioId),
            invite = null,
        )
        assertTrue(trust is WailoHandshake.Trust.Paired)

        val second = handshake(studio, trust)
        val done = second.step as WailoHandshake.Step.Done
        assertFalse(studio.lastFirstContact)
        assertTrue(done.established.pairing.trustedOnFirstUse)
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

        val trust = WailoHandshake.trustFor(pairing = null, invite = invite)
        val done = handshake(studio, trust).step as WailoHandshake.Step.Done
        // Nothing was trusted on faith: the QR carried both the key and the secret.
        assertFalse(done.established.pairing.trustedOnFirstUse)
        assertFalse(studio.lastFirstContact)
        assertArrayEquals(studio.publicKey, done.established.pairing.publicKey)
    }

    @Test
    fun aTypedCodeAuthenticatesOnTheFirstConnection() {
        val code = "ABCDEFGHJK"
        val studio = FakeStudio().apply {
            offerCode = code
            requirePairing = true
        }
        val invite = WailoPairingInvite.fromCode(code, studio.studioId, HOST, 8899)
        assertNotNull(invite)

        val done = handshake(
            studio,
            WailoHandshake.trustFor(pairing = null, invite = invite),
        ).step as WailoHandshake.Step.Done
        assertFalse(studio.lastFirstContact)
        // The code-derived proof authenticates the signed identity before its key is pinned.
        assertArrayEquals(studio.publicKey, done.established.pairing.publicKey)
    }

    /**
     * ClientHello stays anonymous. The signed StudioHello reveals who occupies the remembered address,
     * and the expected identity stops a replacement before the device sends its alias (ADR-0060).
     */
    @Test
    fun aChangedIdentityAtAPinnedAddressStopsTheHandshake() {
        val original = FakeStudio()
        val paired = handshake(original, WailoHandshake.Trust.FirstContact(null)).step as WailoHandshake.Step.Done
        WailoPairingStore.save(paired.established.pairing)

        val impostor = FakeStudio()
        val step = handshake(
            impostor,
            WailoHandshake.trustFor(
                pairing = WailoEndpointResolver.pairingFor(HOST, null),
                invite = null,
            ),
        ).step

        val changed = step as WailoHandshake.Step.IdentityChanged
        assertEquals(original.studioId, changed.expected)
        assertEquals(impostor.studioId, changed.actual)
    }

    @Test
    fun aStudioThatOnlyTakesPairedDevicesRefusesWithAReason() {
        val studio = FakeStudio().apply { requirePairing = true }
        val refused = handshake(studio, WailoHandshake.Trust.FirstContact(null)).step as WailoHandshake.Step.Refused
        assertTrue(refused.reason.isNotEmpty())
    }

    @Test
    fun aStrictStudioAcceptsAnInvitedQr() {
        val studio = FakeStudio().apply {
            requirePairing = true
            offer = WailoCrypto.randomSecret()
        }
        val invite = requireNotNull(WailoPairingInvite.fromQr(studio.qrPayload(HOST, 8899)))

        val done = handshake(
            studio,
            WailoHandshake.trustFor(pairing = null, invite = invite),
        ).step as WailoHandshake.Step.Done

        assertFalse(done.established.pairing.trustedOnFirstUse)
    }

    @Test
    fun anExpiredInviteProducesAnAuthenticatedRefusal() {
        val studio = FakeStudio().apply {
            requirePairing = true
            offer = WailoCrypto.randomSecret()
        }
        val invite = requireNotNull(WailoPairingInvite.fromQr(studio.qrPayload(HOST, 8899)))
        studio.offer = null

        val refused = handshake(
            studio,
            WailoHandshake.trustFor(pairing = null, invite = invite),
        ).step as WailoHandshake.Step.Refused

        assertTrue(refused.reason.contains("expired", ignoreCase = true))
    }

    @Test
    fun theSameStudioAuthenticatesAfterItsAddressChanges() {
        val studio = FakeStudio()
        val first = handshake(studio, WailoHandshake.Trust.FirstContact(null)).step as WailoHandshake.Step.Done

        val done = handshake(
            studio,
            WailoHandshake.Trust.Paired(first.established.pairing),
            host = "10.0.0.99",
        ).step as WailoHandshake.Step.Done

        assertEquals(studio.studioId, done.established.pairing.studioId)
        assertEquals("10.0.0.99", done.established.pairing.lastHost)
    }

    @Test
    fun twoStudiosReceiveUnlinkableAliases() {
        val first = handshake(FakeStudio(), WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done
        val second = handshake(FakeStudio(), WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done

        assertFalse(first.established.pairing.deviceAlias == second.established.pairing.deviceAlias)
    }

    @Test
    fun clientHelloDoesNotRevealThePairedAlias() {
        val studio = FakeStudio()
        val pairing = (handshake(studio, WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done).established.pairing

        val hello = WailoHandshake(WailoHandshake.Trust.Paired(pairing), HOST).begin()

        assertNotNull(hello.auth_client_hello_v3)
        assertFalse(String(hello.encode(), Charsets.ISO_8859_1).contains(pairing.deviceAlias))
    }

    @Test
    fun anOfflineForgetReconnectsWithAFreshAliasAndKeepsOtherStudios() {
        val studio = FakeStudio()
        val first = handshake(studio, WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done
        WailoPairingStore.save(first.established.pairing)
        val otherStudio = FakeStudio()
        val other = handshake(otherStudio, WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done
        WailoPairingStore.save(other.established.pairing)
        val staleAlias = first.established.pairing.deviceAlias
        assertTrue(studio.known.containsKey(staleAlias))

        WailoPairingStore.forget(studio.studioId)
        assertEquals(null, WailoPairingStore.pairing(studio.studioId))
        assertEquals(
            other.established.pairing.deviceAlias,
            WailoPairingStore.pairing(otherStudio.studioId)?.deviceAlias,
        )
        val second = handshake(studio, WailoHandshake.Trust.FirstContact(studio.studioId))
            .step as WailoHandshake.Step.Done

        assertFalse(staleAlias == second.established.pairing.deviceAlias)
        assertTrue(studio.known.containsKey(second.established.pairing.deviceAlias))
        assertEquals(
            other.established.pairing.deviceAlias,
            WailoPairingStore.pairing(otherStudio.studioId)?.deviceAlias,
        )
    }

    @Test
    fun studioSideForgetProducesAnAuthenticatedUnknownDeviceRefusal() {
        val studio = FakeStudio()
        val first = handshake(studio, WailoHandshake.Trust.FirstContact(null))
            .step as WailoHandshake.Step.Done
        studio.known.remove(first.established.pairing.deviceAlias)

        val refused = handshake(
            studio,
            WailoHandshake.Trust.Paired(first.established.pairing),
        ).step as WailoHandshake.Step.Refused

        assertTrue(refused.reason.contains("no longer", ignoreCase = true))
    }

    /**
     * The refusal is only worth acting on because a valid signature came first. Without that, anyone on
     * the network could park the device in a re-pair prompt by answering with `ok = false`.
     */
    @Test
    fun aRefusalFromAPeerThatCannotSignIsNotBelieved() {
        val studio = FakeStudio()
        val device = WailoHandshake(WailoHandshake.Trust.FirstContact(null), HOST)
        device.begin()

        val forged = Envelope(
            auth_studio_hello_v3 = AuthStudioHelloV3(
                nonce = WailoCrypto.randomNonce().toByteString(),
                signature = ByteArray(64).toByteString(),
                public_key = studio.publicKey.toByteString(),
                ephemeral_key = WailoCrypto.encodePublicKey(WailoCrypto.generateEphemeral().public).toByteString(),
                pairing_required = false,
            ),
        )
        assertEquals(WailoHandshake.Step.Failed, device.handle(forged))
    }

    @Test
    fun forgedResultSignatureAndProofAreRejected() {
        val forgedStudios = listOf(
            FakeStudio().apply { forgeResultSignature = true },
            FakeStudio().apply { forgeResultProof = true },
        )

        for (studio in forgedStudios) {
            assertEquals(
                WailoHandshake.Step.Failed,
                handshake(studio, WailoHandshake.Trust.FirstContact(null)).step,
            )
        }
    }

    /** Anything that is not an auth frame, before auth completes, is a peer that skipped the handshake. */
    @Test
    fun aPeerThatSkipsTheHandshakeIsHungUpOn() {
        val device = WailoHandshake(WailoHandshake.Trust.FirstContact(null), HOST)
        device.begin()
        assertEquals(WailoHandshake.Step.Failed, device.handle(Envelope(rule_ack = RuleAck(epoch = 1))))
    }

    @Test
    fun aV2ChallengeIsRejectedAsADowngrade() {
        val device = WailoHandshake(WailoHandshake.Trust.FirstContact(null), HOST)
        device.begin()

        assertEquals(
            WailoHandshake.Step.Failed,
            device.handle(Envelope.ADAPTER.decode(byteArrayOf(0x72, 0x00))),
        )
    }

    // MARK: - driver

    private class Exchange(val step: WailoHandshake.Step, val studioCodec: WailoFrameCodec?)

    /** Runs both halves to a terminal step, passing envelopes straight across in memory. */
    private fun handshake(
        studio: FakeStudio,
        trust: WailoHandshake.Trust,
        host: String = HOST,
    ): Exchange {
        val session = FakeStudioSession(studio)
        val device = WailoHandshake(trust, host)
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
