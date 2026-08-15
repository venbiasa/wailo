package com.venbiasa.wailo.engine.pairing

import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.protocol.AuthDeviceProofV3
import com.venbiasa.wailo.protocol.AuthModeV3
import com.venbiasa.wailo.protocol.AuthResultCodeV3
import com.venbiasa.wailo.protocol.AuthResultV3
import com.venbiasa.wailo.protocol.AuthStudioHelloV3
import com.venbiasa.wailo.protocol.Envelope
import com.venbiasa.wailo.protocol.SealedFrame
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Why a device was turned away, for the notice Studio shows and the reason the device is given. */
data class RefusedDevice(val deviceId: String, val reason: String)

/**
 * Outcome of admitting one connection. [Sealed] carries a connection that looks exactly like the
 * plain one to the rest of the engine and quietly encrypts everything through it.
 */
sealed interface Admission {
    data class Sealed(
        val connection: DeviceConnection,
        val deviceId: String,
        val suspectedClone: Boolean,
        /** True the first time a device was admitted leniently, so the UI can say where trust came from. */
        val firstContact: Boolean = false,
    ) : Admission

    data class Refused(val refusal: RefusedDevice) : Admission

    /** The peer failed to prove anything. Nothing is said back; the socket just closes. */
    data object Rejected : Admission
}

/** Studio's identity-first Wi-Fi admission gate (ADR-0060). */
class DeviceAdmission(
    private val pairings: PairingManager,
    /** Read per connection, not captured: the user can flip this while devices are connected. */
    private val requirePairing: () -> Boolean = { false },
) {

    suspend fun admit(connection: DeviceConnection): Admission {
        if (connection.isTrusted) {
            return Admission.Sealed(connection, deviceId = connection.id, suspectedClone = false)
        }

        val hello = read(connection)?.auth_client_hello_v3 ?: return Admission.Rejected
        if (hello.version != PROTOCOL_VERSION || hello.nonce.size != WailoCrypto.NONCE_LENGTH) {
            return Admission.Rejected
        }
        val nonceD = hello.nonce.toByteArray()
        val nonceS = WailoCrypto.randomNonce()
        val ephemeralD = hello.ephemeral_key.toByteArray()
        val ephemeral = WailoCrypto.generateEphemeral()
        val ephemeralS = WailoCrypto.encodePublicKey(ephemeral.public)
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralD) ?: return Admission.Rejected
        val pairingRequired = requirePairing()

        send(
            connection,
            Envelope(
                auth_studio_hello_v3 = AuthStudioHelloV3(
                    nonce = nonceS.toByteString(),
                    public_key = pairings.publicKey.toByteString(),
                    ephemeral_key = ephemeralS.toByteString(),
                    signature = pairings.signStudioHelloV3(
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        pairingRequired,
                    ).toByteString(),
                    pairing_required = pairingRequired,
                ),
            ),
        ) || return Admission.Rejected

        val response = read(connection)?.auth_device_proof_v3 ?: return Admission.Rejected
        val alias = response.device_alias
        if (!isValidAlias(alias)) return Admission.Rejected
        val known = pairings.known(alias)
        val selected = when (response.mode) {
            AuthModeV3.AUTH_MODE_V3_KNOWN -> {
                val saved = known ?: return decline(
                    connection,
                    response,
                    nonceD,
                    nonceS,
                    ephemeralD,
                    ephemeralS,
                    pairingRequired,
                    AuthResultCodeV3.AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE,
                    "asked to reconnect with an alias this Studio no longer knows",
                )
                SelectedCredential(saved.key, saved, firstContact = false, trustedOnFirstUse = saved.trustedOnFirstUse)
            }

            AuthModeV3.AUTH_MODE_V3_TOFU -> {
                if (known != null) return Admission.Rejected
                if (pairingRequired) {
                    return decline(
                        connection,
                        response,
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        pairingRequired,
                        AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_REQUIRED,
                        "asked for trust on first use while strict pairing is enabled",
                    )
                }
                SelectedCredential(
                    WailoCrypto.tofuDeviceKeyV3(shared, pairings.studioId, alias),
                    known = null,
                    firstContact = true,
                    trustedOnFirstUse = true,
                )
            }

            AuthModeV3.AUTH_MODE_V3_INVITED_QR,
            AuthModeV3.AUTH_MODE_V3_INVITED_CODE,
            -> {
                if (known != null) return Admission.Rejected
                val key = pairings.inviteKeyV3(
                    alias,
                    pairedByCode = response.mode == AuthModeV3.AUTH_MODE_V3_INVITED_CODE,
                ) ?: return decline(
                    connection,
                    response,
                    nonceD,
                    nonceS,
                    ephemeralD,
                    ephemeralS,
                    pairingRequired,
                    AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID,
                    "presented an expired or absent pairing offer",
                )
                SelectedCredential(key, known = null, firstContact = false, trustedOnFirstUse = false)
            }

            AuthModeV3.AUTH_MODE_V3_UNSPECIFIED -> return Admission.Rejected
        }

        val authKey = WailoCrypto.authKeyV3(shared, selected.key)
        val expected = WailoCrypto
            .deviceProofV3(
                authKey,
                pairings.studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
                pairingRequired,
                alias,
                response.mode.value,
                response.session_counter,
            )
        if (!WailoCrypto.constantTimeEquals(response.proof.toByteArray(), expected)) {
            return Admission.Rejected
        }

        val suspectedClone = selected.known != null &&
            response.session_counter <= selected.known.sessionCounter
        pairings.remember(
            deviceId = alias,
            key = selected.key,
            name = selected.known?.name.orEmpty(),
            sessionCounter = response.session_counter,
            trustedOnFirstUse = selected.trustedOnFirstUse,
        )
        val resultCode = AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK
        send(
            connection,
            Envelope(
                auth_result_v3 = AuthResultV3(
                    code = resultCode,
                    proof = WailoCrypto.studioProofV3(
                        authKey,
                        pairings.studioId,
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        pairingRequired,
                        alias,
                        response.mode.value,
                        response.session_counter,
                        resultCode.value,
                    ).toByteString(),
                    signature = pairings.signResultV3(
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        pairingRequired,
                        alias,
                        response.mode.value,
                        response.session_counter,
                        resultCode.value,
                    ).toByteString(),
                ),
            ),
        ) || return Admission.Rejected

        return Admission.Sealed(
            connection = SealedConnection(
                connection,
                FrameCodec(
                    sessionKey = WailoCrypto.sessionKeyV3(shared, selected.key, nonceD, nonceS),
                    sealing = FrameCodec.Direction.STUDIO_TO_DEVICE,
                    opening = FrameCodec.Direction.DEVICE_TO_STUDIO,
                ),
            ),
            deviceId = alias,
            suspectedClone = suspectedClone,
            firstContact = selected.firstContact,
        )
    }

    private suspend fun decline(
        connection: DeviceConnection,
        request: AuthDeviceProofV3,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
        pairingRequired: Boolean,
        code: AuthResultCodeV3,
        reason: String,
    ): Admission {
        send(
            connection,
            Envelope(
                auth_result_v3 = AuthResultV3(
                    code = code,
                    proof = ByteString.EMPTY,
                    signature = pairings.signResultV3(
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        pairingRequired,
                        request.device_alias,
                        request.mode.value,
                        request.session_counter,
                        code.value,
                    ).toByteString(),
                ),
            ),
        )
        return Admission.Refused(RefusedDevice(request.device_alias, reason))
    }

    private suspend fun read(connection: DeviceConnection): Envelope? =
        runCatching { connection.receive()?.let(Envelope.ADAPTER::decode) }.getOrNull()

    private suspend fun send(connection: DeviceConnection, envelope: Envelope): Boolean =
        runCatching { connection.send(envelope.encode()) }.isSuccess

    private fun isValidAlias(alias: String): Boolean =
        alias.length == DEVICE_ALIAS_HEX_LENGTH && alias.all { it in '0'..'9' || it in 'a'..'f' }

    private data class SelectedCredential(
        val key: ByteArray,
        val known: PairedDevice?,
        val firstContact: Boolean,
        val trustedOnFirstUse: Boolean,
    )

    private companion object {
        const val PROTOCOL_VERSION = 3
        const val DEVICE_ALIAS_HEX_LENGTH = 32
    }
}

/**
 * A connection whose frames are sealed. Wrapping rather than threading a codec through the engine
 * keeps every existing send and receive untouched: from `WailoEngine`'s side this is still just a
 * `DeviceConnection` carrying Envelopes.
 */
private class SealedConnection(
    private val inner: DeviceConnection,
    private val codec: FrameCodec,
) : DeviceConnection {

    override val id: String = inner.id
    override val transport: DeviceTransport = inner.transport
    override val isTrusted: Boolean = true

    override suspend fun receive(): ByteArray? {
        val bytes = inner.receive() ?: return null
        val frame = runCatching { Envelope.ADAPTER.decode(bytes).sealed_frame }.getOrNull() ?: return null
        // An unsealed frame on a sealed session is not a version mismatch — the handshake that got us
        // here settled the version — so treat it as the injection it would have to be.
        return runCatching { codec.open(frame.seq, frame.ciphertext.toByteArray()) }.getOrNull()
    }

    override suspend fun send(bytes: ByteArray) {
        val sealed = codec.seal(bytes)
        inner.send(
            Envelope(
                sealed_frame = SealedFrame(seq = sealed.seq, ciphertext = sealed.ciphertext.toByteString()),
            ).encode(),
        )
    }

    override fun close() = inner.close()
}
