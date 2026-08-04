package com.venbiasa.wailo.engine.pairing

import com.venbiasa.wailo.engine.DeviceConnection
import com.venbiasa.wailo.engine.DeviceTransport
import com.venbiasa.wailo.protocol.AuthChallenge
import com.venbiasa.wailo.protocol.AuthRequest
import com.venbiasa.wailo.protocol.AuthResult
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

/**
 * Studio's half of the WiFi handshake (ADR-0039, revised by ADR-0040).
 *
 * Runs before `WailoEngine` learns anything about the connection, which is the point: the rule set,
 * the capture filter and the breakpoint rules were previously pushed the moment a socket opened, so
 * anything that dialled the port received a list of the hosts being intercepted and every Map Local
 * path before saying a word. Nothing is pushed now until this returns [Admission.Sealed].
 *
 * Two ways in. A device that already holds a key, or that was handed one by a QR or typed code,
 * proves it. A device with neither is admitted anyway *if* [requirePairing] is off — trust on first
 * use, which is what makes the common case (one developer, one Mac, an address they typed themselves)
 * work without a ceremony. Either way the session is encrypted; the difference is only what was
 * proved before it started.
 */
class DeviceAdmission(
    private val pairings: PairingManager,
    /** Read per connection, not captured: the user can flip this while devices are connected. */
    private val requirePairing: () -> Boolean = { false },
) {

    suspend fun admit(connection: DeviceConnection): Admission {
        // Loopback — the Simulator, `adb reverse`, the usbmux tunnel — reaches a peer the kernel already
        // guarantees is this machine. A key would prove nothing that the address does not.
        if (connection.isTrusted) {
            return Admission.Sealed(connection, deviceId = connection.id, suspectedClone = false)
        }

        val request = read(connection)?.auth_request ?: return Admission.Rejected
        if (request.nonce.size != WailoCrypto.NONCE_LENGTH) return Admission.Rejected
        // Empty means "I do not know who is listening here yet", which only a first contact says. A
        // device naming a different identity is dialling a Studio this one has rotated away from.
        if (request.studio_id.isNotEmpty() && request.studio_id != pairings.studioId) return Admission.Rejected

        val nonceD = request.nonce.toByteArray()
        val nonceS = WailoCrypto.randomNonce()
        val ephemeralD = request.ephemeral_key.toByteArray()
        val ephemeral = WailoCrypto.generateEphemeral()
        val ephemeralS = WailoCrypto.encodePublicKey(ephemeral.public)
        // No agreement, no session — there is no other way to a key, and falling back to one derived
        // from the long-term secret alone would quietly drop forward secrecy for anyone who omits this.
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralD) ?: return Admission.Rejected

        val known = pairings.known(request.device_id)
        val invited = pairings.keyFor(request.device_id, request.paired_by_code)
        val firstContact = known == null && invited == null
        if (firstContact && requirePairing()) {
            return decline(connection, request, nonceD, nonceS, ephemeralD, ephemeralS)
        }

        // A first contact has nothing to prove with, so the agreed secret carries the session on its
        // own. Everyone else mixes their long-term key in, which is what actually authenticates them.
        val deviceKey = known?.key ?: invited
        val authKey = WailoCrypto.authKey(shared, deviceKey)
        send(
            connection,
            Envelope(
                auth_challenge = AuthChallenge(
                    nonce = nonceS.toByteString(),
                    signature = pairings
                        .sign(nonceD, nonceS, ephemeralD, ephemeralS)
                        .toByteString(),
                    mac = WailoCrypto
                        .studioMac(authKey, pairings.studioId, nonceD, nonceS, ephemeralD, ephemeralS)
                        .toByteString(),
                    public_key = pairings.publicKey.toByteString(),
                    ephemeral_key = ephemeralS.toByteString(),
                ),
            ),
        ) || return Admission.Rejected

        val response = read(connection)?.auth_response ?: return Admission.Rejected
        val expected = WailoCrypto
            .deviceProof(authKey, pairings.studioId, nonceD, nonceS, ephemeralD, ephemeralS)
        if (!WailoCrypto.constantTimeEquals(response.proof.toByteArray(), expected)) {
            return Admission.Rejected
        }

        // A counter that has not moved forward means two devices are using one key. It cannot be
        // stopped from here — both hold the same secret — but it can be made visible. A first contact
        // has no history to compare against.
        val suspectedClone = !firstContact && response.session_counter <= (known?.sessionCounter ?: -1)

        // The key a first contact keeps is derived from this connection's agreed secret, by both ends
        // independently, so it is never sent. Every later connection then takes the path above.
        pairings.remember(
            deviceId = request.device_id,
            key = deviceKey ?: WailoCrypto.tofuDeviceKey(shared, pairings.studioId, request.device_id),
            name = known?.name.orEmpty(),
            sessionCounter = response.session_counter,
            trustedOnFirstUse = firstContact || (known?.trustedOnFirstUse ?: false),
        )
        send(connection, Envelope(auth_result = AuthResult(ok = true))) || return Admission.Rejected

        return Admission.Sealed(
            connection = SealedConnection(
                connection,
                FrameCodec(
                    sessionKey = WailoCrypto.sessionKey(shared, deviceKey, nonceD, nonceS),
                    sealing = FrameCodec.Direction.STUDIO_TO_DEVICE,
                    opening = FrameCodec.Direction.DEVICE_TO_STUDIO,
                ),
            ),
            deviceId = request.device_id,
            suspectedClone = suspectedClone,
            firstContact = firstContact,
        )
    }

    /**
     * Turn a device away in a way it can trust. Studio holds no key for it, so it cannot produce a
     * mac — but it can still sign, and a device that has been here before pinned that public key.
     * Sending the signature and omitting the mac is exactly "I am the Studio you know, and I will not
     * take you", which is what lets the device stop retrying instead of reconnecting into the same
     * wall every two seconds. Without the signature any peer on the network could produce that effect.
     */
    private suspend fun decline(
        connection: DeviceConnection,
        request: AuthRequest,
        nonceD: ByteArray,
        nonceS: ByteArray,
        ephemeralD: ByteArray,
        ephemeralS: ByteArray,
    ): Admission {
        send(
            connection,
            Envelope(
                auth_challenge = AuthChallenge(
                    nonce = nonceS.toByteString(),
                    signature = pairings.sign(nonceD, nonceS, ephemeralD, ephemeralS).toByteString(),
                    mac = ByteString.EMPTY,
                    public_key = pairings.publicKey.toByteString(),
                    ephemeral_key = ephemeralS.toByteString(),
                ),
            ),
        )
        send(
            connection,
            Envelope(
                auth_result = AuthResult(
                    ok = false,
                    reason = "This Studio only accepts paired devices. Pair from its Devices panel.",
                ),
            ),
        )
        return Admission.Refused(RefusedDevice(request.device_id, "asked to connect but is not paired"))
    }

    private suspend fun read(connection: DeviceConnection): Envelope? =
        runCatching { connection.receive()?.let(Envelope.ADAPTER::decode) }.getOrNull()

    private suspend fun send(connection: DeviceConnection, envelope: Envelope): Boolean =
        runCatching { connection.send(envelope.encode()) }.isSuccess
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
