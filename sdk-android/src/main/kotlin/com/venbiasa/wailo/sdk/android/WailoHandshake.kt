package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthChallenge
import com.venbiasa.wailo.protocol.AuthRequest
import com.venbiasa.wailo.protocol.AuthResponse
import com.venbiasa.wailo.protocol.AuthResult
import com.venbiasa.wailo.protocol.Envelope
import okio.ByteString.Companion.toByteString

/**
 * Drives the device's half of the WiFi handshake (ADR-0039, revised by ADR-0040): one exchange, no I/O
 * of its own, so the whole trust decision is testable without a socket. The Kotlin mirror of
 * `sdk-ios/Sources/WailoSDK/WailoHandshake.swift`.
 *
 * Nothing leaves the device until this finishes — `Hello` carries the device name and package id, and
 * handing those to anything that merely advertises `_wailo._tcp` is the leak the handshake exists to
 * prevent.
 */
internal class WailoHandshake(
    private val trust: Trust,
    private val deviceId: String,
    private val host: String,
) {

    /** What the device already knows about the peer it is dialling, which is what it can demand. */
    sealed interface Trust {
        /**
         * Nothing. The user named this address, so whatever identity answers is pinned and kept
         * (ADR-0040). An attacker has to already be in the path at this exact moment; from the next
         * connection on, the pin makes that too late.
         */
        data object FirstContact : Trust

        /**
         * A QR or typed code supplied the secret out of band. [publicKey] is present for a QR, which
         * carries it; a typed code has to pin whatever the challenge offers, and leans on the mac to
         * prove that key belongs to the Studio showing the code.
         */
        data class Invited(
            val studioId: String,
            val deviceKey: ByteArray,
            val publicKey: ByteArray?,
            val byCode: Boolean,
        ) : Trust

        /** Established previously: both the key and the identity are pinned, and neither may move. */
        data class Paired(
            val studioId: String,
            val deviceKey: ByteArray,
            val publicKey: ByteArray,
            val sessionCounter: Long,
        ) : Trust
    }

    class Established(val sessionKey: ByteArray, val pairing: WailoPairing)

    sealed interface Step {
        data class Send(val envelope: Envelope) : Step
        data class Done(val established: Established) : Step

        /**
         * Studio does not know us, or will not take an unpaired device. Deliberately distinct from
         * [Failed]: the device stops retrying but keeps its key, because `AuthResult` is
         * unauthenticated and deleting on it would let anyone force a re-pair on demand.
         */
        data class Refused(val reason: String) : Step

        /**
         * Something else is answering at an address this device has been to before. Never resolved
         * automatically — that decision is the entire value of having pinned the key (ADR-0040).
         */
        data class IdentityChanged(val expected: String, val actual: String) : Step

        /** Something claimed this identity and could not prove it. Hang up without a word. */
        data object Failed : Step
        data object Ignore : Step
    }

    private val nonceD = WailoCrypto.randomNonce()
    private val ephemeral = WailoCrypto.generateEphemeral()
    private val ephemeralD = WailoCrypto.encodePublicKey(ephemeral.public)

    private var nonceS: ByteArray? = null
    private var shared: ByteArray? = null
    private var resolvedStudioId: String? = null
    private var acceptedPublicKey: ByteArray? = null

    /**
     * Studio proved its identity but declined to prove it holds our key. Only then is the `AuthResult`
     * that follows worth acting on.
     */
    private var declining = false

    fun begin(): Envelope = Envelope(
        auth_request = AuthRequest(
            // Empty on a first contact: the device has not been told who is listening here, and
            // guessing would only produce a mismatch Studio has to reject.
            studio_id = expectedStudioId.orEmpty(),
            device_id = deviceId,
            nonce = nonceD.toByteString(),
            paired_by_code = pairedByCode,
            ephemeral_key = ephemeralD.toByteString(),
        ),
    )

    fun handle(envelope: Envelope): Step {
        envelope.auth_challenge?.let { return handle(it) }
        envelope.auth_result?.let { return handle(it) }
        // Anything else before auth completes is a peer that skipped the handshake.
        return Step.Failed
    }

    private fun handle(challenge: AuthChallenge): Step {
        if (nonceS != null || challenge.nonce.size != WailoCrypto.NONCE_LENGTH) return Step.Failed
        val nonceS = challenge.nonce.toByteArray()
        val ephemeralS = challenge.ephemeral_key.toByteArray()
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralS) ?: return Step.Failed

        val publicKey = when (val identity = resolveIdentity(challenge.public_key.toByteArray())) {
            is Identity.Use -> identity.publicKey
            is Identity.Reject -> return identity.step
        }
        val studioId = WailoCrypto.studioId(publicKey)

        val signed = WailoCrypto.isValidStudioSignature(
            challenge.signature.toByteArray(), publicKey, studioId, nonceD, nonceS, ephemeralD, ephemeralS,
        )
        if (!signed) return Step.Failed

        this.nonceS = nonceS
        this.shared = shared
        this.resolvedStudioId = studioId

        // No mac means Studio can sign as itself but will not answer under our key: it has either
        // forgotten this device or refuses unpaired ones. Requiring the signature first is what makes
        // the refusal that follows trustworthy — otherwise anyone on the network could send one and
        // park the device in a re-pair prompt.
        if (challenge.mac.size == 0) {
            declining = true
            return Step.Ignore
        }

        val authKey = WailoCrypto.authKey(shared, deviceKey)
        val expectedMac = WailoCrypto.studioMac(authKey, studioId, nonceD, nonceS, ephemeralD, ephemeralS)
        if (!WailoCrypto.constantTimeEquals(challenge.mac.toByteArray(), expectedMac)) return Step.Failed

        acceptedPublicKey = publicKey
        val proof = WailoCrypto.deviceProof(authKey, studioId, nonceD, nonceS, ephemeralD, ephemeralS)
        return Step.Send(
            Envelope(
                auth_response = AuthResponse(
                    proof = proof.toByteString(),
                    session_counter = sessionCounter + 1,
                ),
            ),
        )
    }

    private sealed interface Identity {
        class Use(val publicKey: ByteArray) : Identity
        class Reject(val step: Step) : Identity
    }

    /**
     * Which key to verify against, and whether the answer is acceptable at all.
     *
     * The pinned case is the one that matters: an address this device has used before, now answered by
     * a different identity, is either a machine that changed hands or someone standing in the path.
     * Both look identical from here, so neither is resolved automatically.
     */
    private fun resolveIdentity(offered: ByteArray): Identity {
        val pinned = pinnedPublicKey
        if (pinned != null) {
            if (offered.isNotEmpty() && !offered.contentEquals(pinned)) {
                return Identity.Reject(
                    Step.IdentityChanged(
                        expected = WailoCrypto.studioId(pinned),
                        actual = WailoCrypto.studioId(offered),
                    ),
                )
            }
            return Identity.Use(pinned)
        }
        // Nothing pinned: take what is offered, but a named identity still has to match its own
        // fingerprint, which keeps "sid is the hash of the key" true even here.
        if (offered.isEmpty()) return Identity.Reject(Step.Failed)
        val expected = expectedStudioId
        if (expected != null && WailoCrypto.studioId(offered) != expected) return Identity.Reject(Step.Failed)
        return Identity.Use(offered)
    }

    private fun handle(result: AuthResult): Step {
        if (declining) {
            return Step.Refused(result.reason.ifEmpty { "This Studio will not accept this device." })
        }
        val nonceS = this.nonceS ?: return Step.Failed
        val shared = this.shared ?: return Step.Failed
        val studioId = resolvedStudioId ?: return Step.Failed
        val acceptedPublicKey = this.acceptedPublicKey ?: return Step.Failed
        // A rejection at this point came from a peer that already proved it holds our key, so it is
        // worth surfacing rather than silently retrying.
        if (!result.ok) {
            return Step.Refused(result.reason.ifEmpty { "This Studio does not recognise this device." })
        }
        // On a first contact the long-term key is derived from this connection's agreed secret, by both
        // ends independently, so it never crosses the wire.
        val longTerm = deviceKey ?: WailoCrypto.tofuDeviceKey(shared, studioId, deviceId)
        return Step.Done(
            Established(
                sessionKey = WailoCrypto.sessionKey(shared, deviceKey, nonceD, nonceS),
                pairing = WailoPairing(
                    studioId = studioId,
                    deviceKey = longTerm,
                    publicKey = acceptedPublicKey,
                    sessionCounter = sessionCounter + 1,
                    refused = false,
                    lastHost = host,
                    trustedOnFirstUse = deviceKey == null,
                ),
            ),
        )
    }

    // MARK: - what the trust mode supplies

    private val expectedStudioId: String?
        get() = when (trust) {
            Trust.FirstContact -> null
            is Trust.Invited -> trust.studioId
            is Trust.Paired -> trust.studioId
        }

    private val deviceKey: ByteArray?
        get() = when (trust) {
            Trust.FirstContact -> null
            is Trust.Invited -> trust.deviceKey
            is Trust.Paired -> trust.deviceKey
        }

    private val pinnedPublicKey: ByteArray?
        get() = when (trust) {
            Trust.FirstContact -> null
            is Trust.Invited -> trust.publicKey
            is Trust.Paired -> trust.publicKey
        }

    private val sessionCounter: Long
        get() = when (trust) {
            Trust.FirstContact, is Trust.Invited -> 0
            is Trust.Paired -> trust.sessionCounter
        }

    private val pairedByCode: Boolean
        get() = when (trust) {
            is Trust.Invited -> trust.byCode
            Trust.FirstContact, is Trust.Paired -> false
        }

    companion object {
        /**
         * What this device may demand of the peer, given what it already holds for it. Called once per
         * connection attempt rather than once per client, which is the whole of ADR-0046's WiFi bug:
         * iOS captured the `Trust` when it built the client and then reconnected internally, so every
         * reconnect after a successful first contact re-introduced a device Studio had *just* stored a
         * key for, derived the auth key without that key, and failed the mac check — silently, and
         * identically forever. Re-reading the pairing per attempt makes that unrepresentable here.
         */
        fun trustFor(pairing: WailoPairing?, invite: WailoPairingInvite?, deviceId: String): Trust = when {
            invite != null -> Trust.Invited(
                studioId = invite.studioId,
                deviceKey = WailoCrypto.deviceKey(invite.pairingSecret, invite.studioId, deviceId),
                publicKey = invite.publicKey,
                byCode = invite.pairedByCode,
            )

            pairing != null -> Trust.Paired(
                studioId = pairing.studioId,
                deviceKey = pairing.deviceKey,
                publicKey = pairing.publicKey,
                sessionCounter = pairing.sessionCounter,
            )

            else -> Trust.FirstContact
        }
    }
}
