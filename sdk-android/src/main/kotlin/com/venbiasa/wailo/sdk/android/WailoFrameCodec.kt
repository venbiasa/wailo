package com.venbiasa.wailo.sdk.android

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A frame that will not open on a sealed session: a replay, an injection, or a wrong key. */
internal class FrameRejected(message: String) : Exception(message)

/**
 * Seals and opens the frames of one WiFi session (ADR-0039). The device-side mirror of
 * `engine/pairing/FrameCodec.kt` and `sdk-ios/Sources/WailoSDK/WailoFrameCodec.swift`, with the
 * directions swapped.
 *
 * Authentication alone would leave the stream readable to anyone sniffing a WPA2-PSK network and
 * rewritable by an on-path relay that passes the handshake through untouched, since nothing would bind
 * the proven identity to the data channel. Sealing under a key neither attacker can derive closes both.
 *
 * AES-256-GCM rather than ChaCha20-Poly1305 because this is the side that decided it: `javax.crypto`
 * only offers ChaCha at API 28 and the SDK's floor is 24.
 */
internal class WailoFrameCodec(
    sessionKey: ByteArray,
    private val sealing: Direction,
    private val opening: Direction,
) {
    /**
     * The high half of the GCM nonce. The two directions differ so device and Studio counters cannot
     * collide under one session key — the failure GCM does not survive.
     */
    enum class Direction(val id: Int) {
        DEVICE_TO_STUDIO(1),
        STUDIO_TO_DEVICE(2),
    }

    private val key = SecretKeySpec(sessionKey, "AES")
    private var nextSeq = 0L
    private var lastOpened: Long? = null

    data class Sealed(val seq: Long, val ciphertext: ByteArray)

    @Synchronized
    fun seal(plaintext: ByteArray): Sealed {
        val seq = nextSeq++
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(sealing, seq)))
        }
        // `AES/GCM/NoPadding` appends the tag, which is the ciphertext ++ tag layout CryptoKit reads.
        return Sealed(seq, cipher.doFinal(plaintext))
    }

    @Synchronized
    fun open(seq: Long, ciphertext: ByteArray): ByteArray {
        val last = lastOpened
        // Over TCP frames arrive in order, so a counter that does not advance is a replay.
        if (last != null && seq <= last) throw FrameRejected("frame $seq is not newer than $last")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(opening, seq)))
        }
        val plaintext = runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { throw FrameRejected("frame $seq failed authentication") }
        lastOpened = seq
        return plaintext
    }

    /**
     * 4-byte direction ++ 8-byte big-endian counter. `seq` travels in the clear, but GCM folds the
     * nonce into the tag, so altering it in flight just fails the open.
     */
    private fun nonce(direction: Direction, seq: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).putInt(direction.id).putLong(seq).array()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_BYTES = 12
    }
}
