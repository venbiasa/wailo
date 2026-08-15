package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.AuthClientHelloV3
import com.venbiasa.wailo.protocol.AuthDeviceProofV3
import com.venbiasa.wailo.protocol.AuthModeV3
import com.venbiasa.wailo.protocol.AuthResultCodeV3
import com.venbiasa.wailo.protocol.AuthResultV3
import com.venbiasa.wailo.protocol.AuthStudioHelloV3
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
    private val host: String,
) {

    /** What the device already knows about the peer it is dialling, which is what it can demand. */
    sealed interface Trust {
        /**
         * Nothing. The user named this address, so whatever identity answers is pinned and kept
         * (ADR-0040). An attacker has to already be in the path at this exact moment; from the next
         * connection on, the pin makes that too late.
         */
        data class FirstContact(val expectedStudioId: String?) : Trust

        data class KnownOrFirstContact(
            val pairings: List<WailoPairing>,
            val expectedStudioId: String?,
        ) : Trust

        /**
         * A QR or typed code supplied the secret out of band. [publicKey] is present for a QR, which
         * carries it; a typed code pins the signed StudioHello identity after the code-derived proof
         * authenticates it.
         */
        data class Invited(
            val studioId: String,
            val pairingSecret: ByteArray,
            val publicKey: ByteArray?,
            val byCode: Boolean,
        ) : Trust

        /** Established previously: both the key and the identity are pinned, and neither may move. */
        data class Paired(val pairing: WailoPairing) : Trust
    }

    class Established(val sessionKey: ByteArray, val pairing: WailoPairing)

    sealed interface Step {
        data class Send(val envelope: Envelope) : Step
        data class Done(val established: Established) : Step

        /**
         * Studio authenticated a refusal. Distinct from [Failed] so the UI can offer explicit
         * Retry/Forget recovery instead of silently looping.
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
    private val pendingDeviceAlias = WailoPairingStore.newDeviceAlias()

    private var nonceS: ByteArray? = null
    private var ephemeralS: ByteArray? = null
    private var shared: ByteArray? = null
    private var resolvedStudioId: String? = null
    private var acceptedPublicKey: ByteArray? = null
    private var resolvedPairing: WailoPairing? = null
    private var deviceAlias: String? = null
    private var selectedDeviceKey: ByteArray? = null
    private var mode: AuthModeV3? = null
    private var sessionCounter: Long? = null
    private var pairingRequired: Boolean = false

    fun begin(): Envelope = Envelope(
        auth_client_hello_v3 = AuthClientHelloV3(
            version = 3,
            nonce = nonceD.toByteString(),
            ephemeral_key = ephemeralD.toByteString(),
        ),
    )

    fun handle(envelope: Envelope): Step {
        envelope.auth_studio_hello_v3?.let { return handle(it) }
        envelope.auth_result_v3?.let { return handle(it) }
        // Anything else before auth completes is a peer that skipped the handshake.
        return Step.Failed
    }

    private fun handle(hello: AuthStudioHelloV3): Step {
        if (nonceS != null || hello.nonce.size != WailoCrypto.NONCE_LENGTH) return Step.Failed
        val nonceS = hello.nonce.toByteArray()
        val ephemeralS = hello.ephemeral_key.toByteArray()
        val shared = WailoCrypto.agree(ephemeral.private, ephemeralS) ?: return Step.Failed
        val offered = hello.public_key.toByteArray()
        if (offered.isEmpty()) return Step.Failed
        val studioId = WailoCrypto.studioId(offered)
        if (!WailoCrypto.isValidStudioHelloV3(
                hello.signature.toByteArray(),
                offered,
                studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
                hello.pairing_required,
            )
        ) {
            return Step.Failed
        }

        val publicKey = when (val identity = resolveIdentity(offered, studioId)) {
            is Identity.Use -> identity.publicKey
            is Identity.Reject -> return identity.step
        }

        this.nonceS = nonceS
        this.ephemeralS = ephemeralS
        this.shared = shared
        this.resolvedStudioId = studioId
        acceptedPublicKey = publicKey
        pairingRequired = hello.pairing_required
        val selected = credential(shared, studioId)
        deviceAlias = selected.alias
        selectedDeviceKey = selected.key
        mode = selected.mode
        sessionCounter = selected.counter
        val authKey = WailoCrypto.authKeyV3(shared, selected.key)
        return Step.Send(
            Envelope(
                auth_device_proof_v3 = AuthDeviceProofV3(
                    device_alias = selected.alias,
                    mode = selected.mode,
                    session_counter = selected.counter,
                    proof = WailoCrypto.deviceProofV3(
                        authKey,
                        studioId,
                        nonceD,
                        nonceS,
                        ephemeralD,
                        ephemeralS,
                        hello.pairing_required,
                        selected.alias,
                        selected.mode.value,
                        selected.counter,
                    ).toByteString(),
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
    private fun resolveIdentity(offered: ByteArray, studioId: String): Identity {
        return when (val current = trust) {
            is Trust.FirstContact -> {
                if (current.expectedStudioId != null && current.expectedStudioId != studioId) {
                    return Identity.Reject(Step.IdentityChanged(current.expectedStudioId, studioId))
                }
                Identity.Use(offered)
            }
            is Trust.KnownOrFirstContact -> {
                if (current.expectedStudioId != null && current.expectedStudioId != studioId) {
                    return Identity.Reject(Step.IdentityChanged(current.expectedStudioId, studioId))
                }
                current.pairings.firstOrNull { it.studioId == studioId }?.let { pairing ->
                    if (!offered.contentEquals(pairing.publicKey)) return Identity.Reject(Step.Failed)
                    resolvedPairing = pairing
                }
                Identity.Use(offered)
            }

            is Trust.Invited -> {
                if (studioId != current.studioId) return Identity.Reject(Step.Failed)
                if (current.publicKey != null && !offered.contentEquals(current.publicKey)) {
                    return Identity.Reject(Step.Failed)
                }
                Identity.Use(offered)
            }

            is Trust.Paired -> {
                val pairing = current.pairing
                if (studioId != pairing.studioId || !offered.contentEquals(pairing.publicKey)) {
                    return Identity.Reject(
                        Step.IdentityChanged(
                            expected = pairing.studioId,
                            actual = studioId,
                        ),
                    )
                }
                resolvedPairing = pairing
                Identity.Use(offered)
            }
        }
    }

    private fun handle(result: AuthResultV3): Step {
        val nonceS = this.nonceS ?: return Step.Failed
        val ephemeralS = this.ephemeralS ?: return Step.Failed
        val shared = this.shared ?: return Step.Failed
        val studioId = resolvedStudioId ?: return Step.Failed
        val acceptedPublicKey = this.acceptedPublicKey ?: return Step.Failed
        val deviceAlias = this.deviceAlias ?: return Step.Failed
        val selectedDeviceKey = this.selectedDeviceKey ?: return Step.Failed
        val mode = this.mode ?: return Step.Failed
        val sessionCounter = this.sessionCounter ?: return Step.Failed
        if (!WailoCrypto.isValidResultSignatureV3(
                result.signature.toByteArray(),
                acceptedPublicKey,
                studioId,
                nonceD,
                nonceS,
                ephemeralD,
                ephemeralS,
                pairingRequired,
                deviceAlias,
                mode.value,
                sessionCounter,
                result.code.value,
            )
        ) {
            return Step.Failed
        }

        if (result.code != AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK) {
            return Step.Refused(refusalMessage(result.code))
        }
        val authKey = WailoCrypto.authKeyV3(shared, selectedDeviceKey)
        val expectedProof = WailoCrypto.studioProofV3(
            authKey,
            studioId,
            nonceD,
            nonceS,
            ephemeralD,
            ephemeralS,
            pairingRequired,
            deviceAlias,
            mode.value,
            sessionCounter,
            result.code.value,
        )
        if (!WailoCrypto.constantTimeEquals(result.proof.toByteArray(), expectedProof)) return Step.Failed

        return Step.Done(
            Established(
                sessionKey = WailoCrypto.sessionKeyV3(shared, selectedDeviceKey, nonceD, nonceS),
                pairing = WailoPairing(
                    studioId = studioId,
                    deviceAlias = deviceAlias,
                    deviceKey = selectedDeviceKey,
                    publicKey = acceptedPublicKey,
                    sessionCounter = sessionCounter,
                    refused = false,
                    lastHost = host,
                    trustedOnFirstUse = resolvedPairing?.trustedOnFirstUse
                        ?: (mode == AuthModeV3.AUTH_MODE_V3_TOFU),
                ),
            ),
        )
    }

    private data class Credential(
        val alias: String,
        val key: ByteArray,
        val mode: AuthModeV3,
        val counter: Long,
    )

    private fun credential(shared: ByteArray, studioId: String): Credential {
        resolvedPairing?.let { pairing ->
            return Credential(
                pairing.deviceAlias,
                pairing.deviceKey,
                AuthModeV3.AUTH_MODE_V3_KNOWN,
                pairing.sessionCounter + 1,
            )
        }
        return when (val current = trust) {
            is Trust.Invited -> Credential(
                pendingDeviceAlias,
                WailoCrypto.deviceKeyV3(current.pairingSecret, studioId, pendingDeviceAlias),
                if (current.byCode) {
                    AuthModeV3.AUTH_MODE_V3_INVITED_CODE
                } else {
                    AuthModeV3.AUTH_MODE_V3_INVITED_QR
                },
                1,
            )

            is Trust.FirstContact, is Trust.KnownOrFirstContact -> Credential(
                pendingDeviceAlias,
                WailoCrypto.tofuDeviceKeyV3(shared, studioId, pendingDeviceAlias),
                AuthModeV3.AUTH_MODE_V3_TOFU,
                1,
            )

            is Trust.Paired -> error("a paired trust always resolves before credential selection")
        }
    }

    private fun refusalMessage(code: AuthResultCodeV3): String = when (code) {
        AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_REQUIRED ->
            "This Studio only accepts paired devices. Pair from its Devices panel."
        AuthResultCodeV3.AUTH_RESULT_CODE_V3_PAIRING_OFFER_INVALID ->
            "The pairing offer expired or was already used. Start pairing again in Studio."
        AuthResultCodeV3.AUTH_RESULT_CODE_V3_UNKNOWN_DEVICE ->
            "This Studio no longer recognises this device. Forget it here, then connect or pair again."
        AuthResultCodeV3.AUTH_RESULT_CODE_V3_OK,
        AuthResultCodeV3.AUTH_RESULT_CODE_V3_UNSPECIFIED,
        -> "This Studio did not accept this device."
    }

    companion object {
        /**
         * What this device may demand of the peer, given what it already holds for it. Called once per
         * connection attempt rather than once per client, which is the whole of ADR-0046's WiFi bug:
         * iOS captured the `Trust` when it built the client and then reconnected internally, so every
         * reconnect after a successful first contact re-introduced a device Studio had *just* stored a
         * key for, derived the auth key without that key, and failed authentication — silently, and
         * identically forever. Re-reading the pairing per attempt makes that unrepresentable here.
         */
        fun trustFor(
            pairing: WailoPairing?,
            invite: WailoPairingInvite?,
            candidates: List<WailoPairing> = emptyList(),
            expectedStudioId: String? = null,
        ): Trust = when {
            invite != null -> Trust.Invited(
                studioId = invite.studioId,
                pairingSecret = invite.pairingSecret,
                publicKey = invite.publicKey,
                byCode = invite.pairedByCode,
            )

            pairing != null -> Trust.Paired(pairing)

            candidates.isNotEmpty() -> Trust.KnownOrFirstContact(candidates, expectedStudioId)

            else -> Trust.FirstContact(expectedStudioId)
        }
    }
}
