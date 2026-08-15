package com.venbiasa.wailo.engine.pairing

import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.protocol.AuthClientHelloV3
import com.venbiasa.wailo.protocol.AuthDeviceProofV3
import com.venbiasa.wailo.protocol.AuthModeV3
import com.venbiasa.wailo.protocol.AuthResultCodeV3
import com.venbiasa.wailo.protocol.AuthResultV3
import com.venbiasa.wailo.protocol.Envelope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAdmissionTest {

    @Test
    fun `explicit unknown device is admitted by tofu and can reconnect as known`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val first = exchange(manager, alias = ALIAS, mode = AuthModeV3.AUTH_MODE_V3_TOFU)

        assertIs<Admission.Sealed>(first.admission)
        assertTrue(first.admission.firstContact)
        assertEquals(AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK, first.result?.code)
        assertAuthenticatedResult(manager, first)
        val stored = assertNotNull(manager.known(ALIAS))
        assertTrue(stored.trustedOnFirstUse)

        val second = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_KNOWN,
            deviceKey = stored.key,
            counter = 2,
        )
        assertIs<Admission.Sealed>(second.admission)
        assertFalse(second.admission.firstContact)
        assertFalse(second.admission.suspectedClone)
        assertEquals(2L, manager.known(ALIAS)?.sessionCounter)
        assertAuthenticatedResult(manager, second)
    }

    @Test
    fun `a non-increasing known counter is admitted but flagged as a suspected clone`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        exchange(manager, alias = ALIAS, mode = AuthModeV3.AUTH_MODE_V3_TOFU, counter = 5)
        val stored = assertNotNull(manager.known(ALIAS))

        val replayedCounter = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_KNOWN,
            deviceKey = stored.key,
            counter = 5,
        )

        assertIs<Admission.Sealed>(replayedCounter.admission)
        assertTrue(replayedCounter.admission.suspectedClone)
        assertEquals(5L, manager.known(ALIAS)?.sessionCounter)
        assertAuthenticatedResult(manager, replayedCounter)
    }

    @Test
    fun `learning a device name does not erase tofu provenance`() {
        val manager = PairingManager(InMemoryPairingKeyStore())
        manager.remember(
            deviceId = ALIAS,
            key = ByteArray(32) { 1 },
            name = "",
            sessionCounter = 1,
            trustedOnFirstUse = true,
        )

        manager.remember(
            deviceId = ALIAS,
            key = ByteArray(32) { 1 },
            name = "Phone",
            sessionCounter = 1,
        )

        val updated = assertNotNull(manager.known(ALIAS))
        assertEquals("Phone", updated.name)
        assertTrue(updated.trustedOnFirstUse)
    }

    @Test
    fun `strict mode authenticates refusal but accepts qr and code invitations`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val refused = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_TOFU,
            requirePairing = true,
        )
        assertIs<Admission.Refused>(refused.admission)
        assertEquals(AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_REQUIRED, refused.result?.code)
        assertAuthenticatedResult(manager, refused)

        val qr = manager.beginPairing()
        val qrAlias = "11112222333344445555666677778888"
        val byQr = exchange(
            manager,
            alias = qrAlias,
            mode = AuthModeV3.AUTH_MODE_V3_INVITED_QR,
            pairingSecret = qr.qrSecret,
            requirePairing = true,
        )
        assertIs<Admission.Sealed>(byQr.admission)

        val code = manager.beginPairing()
        val codeAlias = "9999aaaabbbbccccddddeeeeffff0000"
        val byCode = exchange(
            manager,
            alias = codeAlias,
            mode = AuthModeV3.AUTH_MODE_V3_INVITED_CODE,
            pairingSecret = WailoCrypto.stretch(code.code, manager.studioId),
            requirePairing = true,
        )
        assertIs<Admission.Sealed>(byCode.admission)
        assertEquals(AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK, byCode.result?.code)
    }

    @Test
    fun `an expired invitation produces an authenticated refusal`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val expired = manager.beginPairing(ttlMs = 1, now = 0)
        assertNull(manager.inviteKeyV3(ALIAS, now = expired.expiresAtEpochMs))

        val refused = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_INVITED_QR,
            pairingSecret = expired.qrSecret,
            requirePairing = true,
        )

        assertIs<Admission.Refused>(refused.admission)
        assertEquals(AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID, refused.result?.code)
        assertAuthenticatedResult(manager, refused)
        assertNull(manager.offer.value)
    }

    @Test
    fun `studio forget yields an authenticated unknown-device result`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        exchange(manager, alias = ALIAS, mode = AuthModeV3.AUTH_MODE_V3_TOFU)
        val key = assertNotNull(manager.known(ALIAS)).key
        manager.forget(ALIAS)

        val refused = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_KNOWN,
            deviceKey = key,
            counter = 2,
        )

        assertIs<Admission.Refused>(refused.admission)
        assertEquals(AuthResultCodeV3.AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE, refused.result?.code)
        assertAuthenticatedResult(manager, refused)
    }

    @Test
    fun `forged proof and v2 downgrade are rejected without an authenticated outcome`() = runBlocking {
        val manager = PairingManager(InMemoryPairingKeyStore())
        val forged = exchange(
            manager,
            alias = ALIAS,
            mode = AuthModeV3.AUTH_MODE_V3_TOFU,
            forgeProof = true,
        )
        assertEquals(Admission.Rejected, forged.admission)
        assertNull(forged.result)
        assertNull(manager.known(ALIAS))

        val connection = FakeConnection()
        // Former Envelope field 13 carrying an empty AuthRequest. V3 reserves the tag, so decoding
        // preserves it only as an unknown field and admission has no downgrade path to dispatch.
        connection.incoming.send(byteArrayOf(0x6a, 0x00))
        connection.incoming.close()
        assertEquals(Admission.Rejected, DeviceAdmission(manager).admit(connection))
        assertNull(connection.outgoing.tryReceive().getOrNull())
    }

    private suspend fun exchange(
        manager: PairingManager,
        alias: String,
        mode: AuthModeV3,
        deviceKey: ByteArray? = null,
        pairingSecret: ByteArray? = null,
        counter: Long = 1,
        requirePairing: Boolean = false,
        forgeProof: Boolean = false,
    ): Exchange = coroutineScope {
        val connection = FakeConnection()
        val admission = async { DeviceAdmission(manager) { requirePairing }.admit(connection) }
        val ephemeral = WailoCrypto.generateEphemeral()
        val nonceD = WailoCrypto.randomNonce()
        val ephemeralD = WailoCrypto.encodePublicKey(ephemeral.public)
        connection.incoming.send(
            Envelope(
                auth_client_hello_v3 = AuthClientHelloV3(
                    version = 3,
                    nonce = nonceD.toByteString(),
                    ephemeral_key = ephemeralD.toByteString(),
                ),
            ).encode(),
        )
        val hello = assertNotNull(Envelope.ADAPTER.decode(connection.outgoing.receive()).auth_studio_hello_v3)
        val nonceS = hello.nonce.toByteArray()
        val ephemeralS = hello.ephemeral_key.toByteArray()
        assertTrue(
            WailoCrypto.verifyStudioHelloV3(
                hello.signature.toByteArray(),
                manager.publicKey,
                manager.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
                requirePairing,
            ),
        )
        val shared = assertNotNull(WailoCrypto.agree(ephemeral.private, ephemeralS))
        val selected = when (mode) {
            AuthModeV3.AUTH_MODE_V3_KNOWN -> assertNotNull(deviceKey)
            AuthModeV3.AUTH_MODE_V3_TOFU -> WailoCrypto.tofuDeviceKeyV3(shared, manager.studioId, alias)
            AuthModeV3.AUTH_MODE_V3_INVITED_QR,
            AuthModeV3.AUTH_MODE_V3_INVITED_CODE,
            -> WailoCrypto.deviceKeyV3(assertNotNull(pairingSecret), manager.studioId, alias)
            AuthModeV3.AUTH_MODE_V3_UNSPECIFIED -> error("unspecified mode is not a test credential")
        }
        val authKey = WailoCrypto.authKeyV3(shared, selected)
        val proof = WailoCrypto.deviceProofV3(
            authKey,
            manager.studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            requirePairing,
            alias,
            mode.value,
            counter,
        ).also { if (forgeProof) it[0] = (it[0].toInt() xor 1).toByte() }
        connection.incoming.send(
            Envelope(
                auth_device_proof_v3 = AuthDeviceProofV3(
                    device_alias = alias,
                    mode = mode,
                    session_counter = counter,
                    proof = proof.toByteString(),
                ),
            ).encode(),
        )

        val outcome = admission.await()
        val resultEnvelope = connection.outgoing.tryReceive().getOrNull()
        val result = resultEnvelope?.let { Envelope.ADAPTER.decode(it).auth_result_v3 }
        Exchange(
            admission = outcome,
            result = result,
            nonceD = nonceD,
            nonceS = nonceS,
            ephemeralD = ephemeralD,
            ephemeralS = ephemeralS,
            pairingRequired = requirePairing,
            alias = alias,
            mode = mode,
            counter = counter,
            authKey = authKey,
        )
    }

    private fun assertAuthenticatedResult(manager: PairingManager, exchange: Exchange) {
        val result = assertNotNull(exchange.result)
        assertTrue(
            WailoCrypto.verifyResultV3(
                result.signature.toByteArray(),
                manager.publicKey,
                manager.studioId,
                exchange.nonceD,
                exchange.nonceS,
                exchange.ephemeralD,
                exchange.ephemeralS,
                exchange.pairingRequired,
                exchange.alias,
                exchange.mode.value,
                exchange.counter,
                result.code.value,
            ),
        )
        if (result.code == AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK) {
            assertContentEquals(
                WailoCrypto.studioProofV3(
                    exchange.authKey,
                    manager.studioId,
                    exchange.nonceD,
                    exchange.nonceS,
                    exchange.ephemeralD,
                    exchange.ephemeralS,
                    exchange.pairingRequired,
                    exchange.alias,
                    exchange.mode.value,
                    exchange.counter,
                    result.code.value,
                ),
                result.proof.toByteArray(),
            )
        }
    }

    private data class Exchange(
        val admission: Admission,
        val result: AuthResultV3?,
        val nonceD: ByteArray,
        val nonceS: ByteArray,
        val ephemeralD: ByteArray,
        val ephemeralS: ByteArray,
        val pairingRequired: Boolean,
        val alias: String,
        val mode: AuthModeV3,
        val counter: Long,
        val authKey: ByteArray,
    )

    private class FakeConnection : DeviceConnection {
        override val id = "lan:test"
        override val transport = DeviceTransport.LAN
        override val isTrusted = false
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            outgoing.send(bytes)
        }

        override fun close() {
            incoming.close()
            outgoing.close()
        }
    }

    private companion object {
        const val ALIAS = "00112233445566778899aabbccddeeff"
    }
}
