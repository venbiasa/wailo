package com.venbiasa.wailo.daemon

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hand-rolled DER writer behind the local root (ADR-0073).
 *
 * Asserted through the JDK's own PKIX validator rather than by comparing bytes: it is the same
 * machinery a TLS handshake runs, so it catches a mis-encoded length, a missing `keyCertSign`, or a
 * chain whose issuer does not match — while a golden-bytes test would only prove the encoder still
 * agrees with itself.
 */
class X509Test {
    private val rootKeys = keys()
    private val root = selfSigned()

    @Test
    fun theRootIsACertificateAuthorityAllowedToSignExactlyOneLevel() {
        assertEquals(3, root.version, "v3, or none of the extensions below are read at all")
        assertEquals(0, root.basicConstraints, "a root that can mint intermediates is more than Wailo needs")
        assertTrue(root.keyUsage[KEY_CERT_SIGN], "without keyCertSign the JDK refuses the whole chain")
        assertTrue(root.keyUsage[CRL_SIGN])
        assertNotNull(root.getExtensionValue("2.5.29.14"), "the subject key identifier is how a chain is found")
        root.verify(rootKeys.public)
    }

    @Test
    fun aLeafValidatesAgainstTheRoot() {
        val leaf = leafFor("api.example.com")

        validate(leaf)

        assertEquals(-1, leaf.basicConstraints, "a leaf must not be able to sign anything")
        assertContains(leaf.extendedKeyUsage, "1.3.6.1.5.5.7.3.1")
        assertEquals(listOf(listOf(2, "api.example.com")), leaf.subjectAlternativeNames.map { it.toList() })
    }

    @Test
    fun anAddressIsTaggedAsOneRatherThanAsAName() {
        val leaf = leafFor("192.168.1.20")

        validate(leaf)

        // Type 7 is iPAddress. Written as a dNSName the certificate still parses and still validates —
        // and then matches nothing, which is the failure this guards.
        assertEquals(listOf(listOf(7, "192.168.1.20")), leaf.subjectAlternativeNames.map { it.toList() })
    }

    @Test
    fun anExtensionLongerThanTheShortFormSurvives() {
        // 251 characters, so the SAN's own length needs the long form while its parents already did.
        val host = List(10) { "subdomain-with-a-long-label-$it" }.joinToString(".") + ".example.com"

        val leaf = leafFor(host)

        validate(leaf)
        assertEquals(listOf(listOf(2, host)), leaf.subjectAlternativeNames.map { it.toList() })
    }

    @Test
    fun aLeafIsRejectedOnceItsValidityHasPassed() {
        val expired = X509.certificate(
            subject = X500Principal("CN=stale.example.com").encoded,
            issuer = root.subjectX500Principal.encoded,
            publicKey = keys().public,
            signingKey = rootKeys.private,
            serial = BigInteger.TEN,
            notBefore = Instant.now().minus(10, ChronoUnit.DAYS),
            notAfter = Instant.now().minus(1, ChronoUnit.DAYS),
            extensions = listOf(X509.basicConstraints(ca = false), X509.subjectAltName("stale.example.com")),
        )

        val failure = runCatching { validate(expired) }.exceptionOrNull()

        assertNotNull(failure, "an expired leaf must not validate — the dates have to be real dates")
    }

    private fun selfSigned(): X509Certificate {
        val name = X500Principal("CN=Wailo Test Root, O=Wailo").encoded
        return X509.certificate(
            subject = name,
            issuer = name,
            publicKey = rootKeys.public,
            signingKey = rootKeys.private,
            serial = BigInteger.ONE,
            notBefore = Instant.now().minus(1, ChronoUnit.DAYS),
            notAfter = Instant.now().plus(825, ChronoUnit.DAYS),
            extensions = listOf(
                X509.basicConstraints(ca = true, pathLength = 0),
                X509.keyUsage(X509.DIGITAL_SIGNATURE, X509.KEY_CERT_SIGN, X509.CRL_SIGN),
                X509.subjectKeyIdentifier(rootKeys.public),
            ),
        )
    }

    private fun leafFor(host: String): X509Certificate = X509.certificate(
        subject = X500Principal("CN=$host").encoded,
        issuer = root.subjectX500Principal.encoded,
        publicKey = keys().public,
        signingKey = rootKeys.private,
        serial = BigInteger(64, SecureRandom()),
        notBefore = Instant.now().minus(1, ChronoUnit.DAYS),
        notAfter = Instant.now().plus(397, ChronoUnit.DAYS),
        extensions = listOf(
            X509.basicConstraints(ca = false),
            X509.keyUsage(X509.DIGITAL_SIGNATURE, X509.KEY_ENCIPHERMENT),
            X509.serverAuth(),
            X509.subjectAltName(host),
        ),
    )

    /** What a client does with the chain a handshake hands it, minus the network. */
    private fun validate(leaf: X509Certificate) {
        val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf))
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
    }

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    private companion object {
        const val KEY_CERT_SIGN = 5
        const val CRL_SIGN = 6
    }
}
