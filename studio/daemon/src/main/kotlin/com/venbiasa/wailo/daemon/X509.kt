package com.venbiasa.wailo.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The smallest X.509 v3 writer that can mint Wailo's local root and the leaves it signs (ADR-0073).
 *
 * Hand-encoded because the JDK has no public API for *creating* a certificate and the usual answer,
 * BouncyCastle, is ~12 MB of provider — half the shipped dist — for one file. It is the same trade
 * `WailoCrypto` already makes for ECDSA signatures, and it is a small one here: the two genuinely hard
 * structures come free, since `PublicKey.encoded` is already a DER SubjectPublicKeyInfo and
 * `X500Principal.encoded` is already a DER Name. What is left is the envelope and four extensions.
 *
 * Nothing in here fails quietly. A malformed certificate is rejected by the `CertificateFactory` below
 * or by the peer's handshake, which is what `ProxyDecryptionTest` drives against a real TLS origin.
 */
internal object X509 {
    /** KeyUsage bit positions, numbered from the most significant bit (RFC 5280 §4.2.1.3). */
    const val DIGITAL_SIGNATURE = 0
    const val KEY_ENCIPHERMENT = 2
    const val KEY_CERT_SIGN = 5
    const val CRL_SIGN = 6

    /**
     * Builds and signs one certificate, then parses it back through the JDK so what a caller receives
     * has already been accepted as well-formed rather than bytes that only fail later, mid-handshake.
     *
     * [issuer] and [subject] are encoded `X500Principal`s. A self-signed root passes the same value for
     * both and its own key as [signingKey].
     */
    fun certificate(
        subject: ByteArray,
        issuer: ByteArray,
        publicKey: PublicKey,
        signingKey: PrivateKey,
        serial: BigInteger,
        notBefore: Instant,
        notAfter: Instant,
        extensions: List<ByteArray>,
    ): X509Certificate {
        val body = sequence(
            explicit(0, integer(BigInteger.TWO)),
            integer(serial),
            ECDSA_SHA256,
            issuer,
            sequence(time(notBefore), time(notAfter)),
            subject,
            publicKey.encoded,
            explicit(3, sequence(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey)
            update(body)
            // ECDSA on the JVM already emits the DER SEQUENCE this BIT STRING is defined to carry.
            sign()
        }
        val der = sequence(body, ECDSA_SHA256, bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun basicConstraints(ca: Boolean, pathLength: Int? = null): ByteArray {
        val fields = buildList {
            // DER drops a field sitting at its default, so a leaf's `cA FALSE` is an empty SEQUENCE
            // rather than an encoded false.
            if (ca) add(boolean(true))
            if (ca && pathLength != null) add(integer(pathLength.toBigInteger()))
        }
        return extension(BASIC_CONSTRAINTS, critical = true, value = sequence(*fields.toTypedArray()))
    }

    fun keyUsage(vararg bits: Int): ByteArray {
        val highest = bits.max()
        val bytes = ByteArray(highest / 8 + 1)
        bits.forEach { bit -> bytes[bit / 8] = (bytes[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte() }
        // A named-bit string is DER-minimal: everything past the last set bit is not written at all.
        return extension(KEY_USAGE, critical = true, value = bitString(bytes, unused = 7 - highest % 8))
    }

    /** Without `serverAuth` a modern client rejects the leaf whatever else it says. */
    fun serverAuth(): ByteArray =
        extension(EXTENDED_KEY_USAGE, critical = false, value = sequence(oid(SERVER_AUTH)))

    /**
     * The one extension that makes a leaf usable: every current client matches the name here and
     * ignores the CN entirely.
     */
    fun subjectAltName(host: String): ByteArray {
        // dNSName is [2] IA5String and iPAddress is [7] OCTET STRING, both implicitly tagged — the tag
        // replaces the type, so only the bytes differ. An IP written as a DNS name matches nothing.
        val name = addressOf(host)
            ?.let { implicit(7, it) }
            ?: implicit(2, host.toByteArray(Charsets.US_ASCII))
        return extension(SUBJECT_ALT_NAME, critical = false, value = sequence(name))
    }

    /**
     * RFC 5280's method 1: SHA-1 over the public key's BIT STRING. SHA-1 is what the specification says
     * and carries no security weight here — the identifier is a hint for building a chain, not a name.
     */
    fun subjectKeyIdentifier(publicKey: PublicKey): ByteArray = extension(
        SUBJECT_KEY_IDENTIFIER,
        critical = false,
        value = octetString(MessageDigest.getInstance("SHA-1").digest(publicKeyBits(publicKey))),
    )

    private fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
        // `critical` defaults to false, so it is written only when true.
        if (critical) sequence(oid(id), boolean(true), octetString(value))
        else sequence(oid(id), octetString(value))

    /** The `subjectPublicKey` bits out of a SubjectPublicKeyInfo: `SEQUENCE { algorithm, BIT STRING }`. */
    private fun publicKeyBits(publicKey: PublicKey): ByteArray {
        val der = publicKey.encoded
        val info = content(der, 0)
        val algorithm = content(der, info.first)
        val bits = content(der, algorithm.last + 1)
        // A BIT STRING's first content byte counts its unused trailing bits, of which a key has none.
        return der.copyOfRange(bits.first + 1, bits.last + 1)
    }

    /** Where the value of the TLV at [offset] lives, so a caller can step over or into it. */
    private fun content(der: ByteArray, offset: Int): IntRange {
        var index = offset + 1
        val first = der[index++].toInt() and 0xFF
        var length = first
        if (first >= 0x80) {
            length = 0
            repeat(first and 0x7F) { length = (length shl 8) or (der[index++].toInt() and 0xFF) }
        }
        return index until index + length
    }

    /**
     * Recognised by shape rather than resolved, because `InetAddress` treats anything that is not a
     * literal as a name to look up — a DNS round-trip inside a handshake. Null means "not an address".
     */
    private fun addressOf(host: String): ByteArray? {
        val literal = host.contains(':') || IPV4.matches(host)
        return if (literal) runCatching { InetAddress.getByName(host).address }.getOrNull() else null
    }

    /**
     * UTCTime through 2049 and GeneralizedTime after it. RFC 5280 puts the boundary there, and a client
     * that follows it reads a two-digit year on the wrong side of 2050 as the wrong century.
     */
    private fun time(at: Instant): ByteArray {
        val utc = at.atOffset(ZoneOffset.UTC)
        return if (utc.year < 2050) {
            tlv(0x17, utc.format(UTC_TIME).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, utc.format(GENERALIZED_TIME).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun sequence(vararg parts: ByteArray): ByteArray =
        tlv(0x30, parts.fold(ByteArray(0), ByteArray::plus))

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

    private fun octetString(value: ByteArray): ByteArray = tlv(0x04, value)

    private fun bitString(value: ByteArray, unused: Int = 0): ByteArray =
        tlv(0x03, byteArrayOf(unused.toByte()) + value)

    /** A constructed, explicitly tagged field: the type it wraps is written inside it. */
    private fun explicit(tag: Int, value: ByteArray): ByteArray = tlv(0xA0 or tag, value)

    /** An implicitly tagged field: the tag replaces the type, so only the value's bytes are written. */
    private fun implicit(tag: Int, value: ByteArray): ByteArray = tlv(0x80 or tag, value)

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(String::toLong)
        val body = ByteArrayOutputStream()
        // The first two arcs share a byte; the rest are base-128, with the high bit set on every byte
        // but the last of each arc.
        body.write((arcs[0] * 40 + arcs[1]).toInt())
        arcs.drop(2).forEach { arc ->
            val chunks = ArrayDeque<Int>()
            var rest = arc
            do {
                chunks.addFirst((rest and 0x7F).toInt())
                rest = rest shr 7
            } while (rest > 0)
            chunks.forEachIndexed { index, chunk ->
                body.write(if (index == chunks.lastIndex) chunk else chunk or 0x80)
            }
        }
        return tlv(0x06, body.toByteArray())
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val header = if (value.size < 0x80) {
            byteArrayOf(tag.toByte(), value.size.toByte())
        } else {
            // Long form: a count of length bytes, then the length itself, big-endian and minimal.
            val length = value.size.toBigInteger().toByteArray().dropWhile { it == ZERO }.toByteArray()
            byteArrayOf(tag.toByte(), (0x80 or length.size).toByte()) + length
        }
        return header + value
    }

    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val KEY_USAGE = "2.5.29.15"
    private const val SUBJECT_KEY_IDENTIFIER = "2.5.29.14"
    private const val SUBJECT_ALT_NAME = "2.5.29.17"
    private const val EXTENDED_KEY_USAGE = "2.5.29.37"
    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val ZERO = 0.toByte()

    /** `ecdsa-with-SHA256`, whose parameters are absent rather than NULL (RFC 5758). */
    private val ECDSA_SHA256 = sequence(oid("1.2.840.10045.4.3.2"))

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'", Locale.ROOT)
    private val GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'", Locale.ROOT)
}
