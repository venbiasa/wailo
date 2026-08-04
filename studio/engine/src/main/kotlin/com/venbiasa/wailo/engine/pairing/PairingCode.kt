package com.venbiasa.wailo.engine.pairing

/**
 * The typed fallback for pairing a device with no usable camera.
 *
 * Crockford base32 — no I, L, O or U — so nothing reads ambiguously off a screen, and ten characters
 * so it stays typeable. That is only ~50 bits, far short of the 256 a scanned QR carries, which is why
 * the code is never used as a key directly: `WailoCrypto.stretch` puts 200k PBKDF2 rounds behind it and
 * the offer expires in two minutes. Both sides derive from the *normalized* string, so the grouping
 * shown on screen is presentation only.
 */
object PairingCode {

    const val LENGTH = 10
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Ten characters drawn from the secret's leading bytes. */
    fun encode(secret: ByteArray): String {
        require(secret.size >= 7) { "need at least 7 bytes to draw 50 bits from" }
        var bits = 0L
        for (index in 0 until 7) bits = (bits shl 8) or (secret[index].toLong() and 0xFF)
        // 7 bytes is 56 bits; keep the top 50 so every character is fully determined.
        bits = bits ushr 6
        return buildString {
            for (position in LENGTH - 1 downTo 0) {
                append(ALPHABET[((bits ushr (position * 5)) and 0x1F).toInt()])
            }
        }
    }

    /** `ABCDE-FGHJK`, which is how it is read aloud and typed. */
    fun format(code: String): String = "${code.take(5)}-${code.drop(5)}"

    /** Accepts the casing and grouping a human types; null for anything outside the alphabet. */
    fun normalize(text: String): String? {
        val stripped = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (stripped.length != LENGTH || stripped.any { it !in ALPHABET }) return null
        return stripped
    }
}
