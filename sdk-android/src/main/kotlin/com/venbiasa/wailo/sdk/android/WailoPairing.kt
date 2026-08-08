package com.venbiasa.wailo.sdk.android

import okio.ByteString.Companion.decodeBase64
import java.net.URI

/**
 * One paired Studio, as the device remembers it. Readable by the panel, but only the SDK may mint or
 * amend one — `@ConsistentCopyVisibility` pulls the generated `copy` down to the constructor's
 * visibility, so a host app cannot hand the handshake a record it made up.
 */
@ConsistentCopyVisibility
data class WailoPairing internal constructor(
    val studioId: String,
    /** The long-term secret `K`. Derived from the pairing secret, never transmitted. */
    internal val deviceKey: ByteArray,
    /**
     * Pinned at pairing, as an X9.63 uncompressed point. Everything after that connection is
     * authenticated against this and nothing else, so a peer advertising the same `sid` with a
     * different key simply fails.
     */
    internal val publicKey: ByteArray,
    /**
     * Advances on each successful handshake. Studio reads a counter that moves backwards as a cloned
     * key — it cannot stop the clone, but it turns a silent compromise into a visible one.
     */
    val sessionCounter: Long,
    /**
     * Set when Studio said it does not know us. Kept rather than acted on: `AuthResult` arrives
     * unauthenticated, so deleting the key here would hand anyone a way to force a re-pair on demand.
     * It only stops the 2-second reconnect loop until a human clears it in the panel.
     */
    val refused: Boolean,
    /**
     * Where this Studio was last reached. Lets a manually typed LAN address resolve back to the
     * identity it belongs to, since a typed IP says nothing about who is listening on it.
     */
    val lastHost: String,
    /**
     * Accepted on first use rather than through a QR or typed code (ADR-0040). Worth showing: nobody
     * proved anything at that moment beyond answering at an address the user typed, so this is the one
     * entry a careful user might want to re-establish deliberately.
     */
    val trustedOnFirstUse: Boolean,
)

/**
 * Everything needed to pair with one Studio, however the user supplied it.
 *
 * A scanned QR fills all of this in one shot, including the public key, so the signature in the
 * handshake is meaningful from the very first connection. A typed code cannot carry a key, so
 * [publicKey] is null and the device pins whatever key `AuthChallenge` offers — safe only because
 * Studio's mac has to prove knowledge of the same code first, and because the key still has to hash to
 * the [studioId] being dialled.
 */
class WailoPairingInvite private constructor(
    val studioId: String,
    val host: String,
    val port: Int,
    /** X9.63 uncompressed point, matching what `AuthChallenge.public_key` carries. */
    internal val publicKey: ByteArray?,
    internal val pairingSecret: ByteArray,
    /**
     * Tells Studio which half of its offer to answer under. The QR's secret and the typed code's are
     * independent so neither is capped by the other's entropy, which leaves Studio unable to guess.
     */
    internal val pairedByCode: Boolean,
) {
    companion object {
        /**
         * Parses the `wailo://pair` URL a Studio QR encodes. Null for anything malformed rather than
         * partially applied — a half-read invite would pair against the wrong identity.
         */
        fun fromQr(text: String): WailoPairingInvite? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "wailo" || uri.host != "pair") return null

            val items = (uri.query ?: return null).split("&")
                .mapNotNull { item ->
                    val separator = item.indexOf('=')
                    if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
                }
                .toMap()

            val studioId = items["sid"]?.takeIf { it.isNotEmpty() } ?: return null
            val host = items["h"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = items["p"]?.toIntOrNull()?.takeIf(WailoAddress.PORT_RANGE::contains) ?: return null
            val secret = items["s"]?.decodeBase64()?.toByteArray() ?: return null
            if (secret.size != WailoCrypto.KEY_LENGTH) return null

            val publicKey = items["k"]?.decodeBase64()?.toByteArray()
            // A key that does not hash to the identity it claims is a malformed invite, not an attack we
            // need to tolerate — refusing here keeps the "sid is the fingerprint" invariant total.
            if (publicKey != null && WailoCrypto.studioId(publicKey) != studioId) return null

            return WailoPairingInvite(studioId, host, port, publicKey, secret, pairedByCode = false)
        }

        /** The typed-code path: the user picks a discovered desktop and types what Studio is showing. */
        fun fromCode(code: String, studioId: String, host: String, port: Int): WailoPairingInvite? {
            val normalized = WailoPairingCode.normalize(code) ?: return null
            return WailoPairingInvite(
                studioId = studioId,
                host = host,
                port = port,
                publicKey = null,
                pairingSecret = WailoCrypto.stretch(normalized, studioId),
                pairedByCode = true,
            )
        }
    }
}

/**
 * The fallback code's alphabet: Crockford base32, which drops I, L, O and U so nothing reads
 * ambiguously off a screen. Ten characters carry ~50 bits, which is only safe to type because
 * [WailoCrypto.stretch] puts a slow KDF behind it.
 *
 * Public because a host that builds its own pairing screen needs the same rules the SDK derives
 * under — what a keystroke may be, how long the code is — and guessing at them means an entry field
 * that accepts input the handshake will silently reject.
 */
object WailoPairingCode {

    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH: Int = 10

    /**
     * Accepts the grouping and casing a human actually types, and rejects anything outside the
     * alphabet — an unrecognised character would otherwise stretch into a silently wrong secret and
     * surface as an unexplained handshake failure.
     */
    fun normalize(text: String): String? {
        val stripped = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (stripped.length != LENGTH || stripped.any { it !in ALPHABET }) return null
        return stripped
    }

    /**
     * Everything typeable kept, everything else dropped, capped at [LENGTH]. For filtering a field on
     * each keystroke, where [normalize] can only answer once the last character has landed. Anything
     * this returns at full length is accepted by [normalize].
     */
    fun sanitize(text: String): String = text.uppercase().filter { it in ALPHABET }.take(LENGTH)
}
