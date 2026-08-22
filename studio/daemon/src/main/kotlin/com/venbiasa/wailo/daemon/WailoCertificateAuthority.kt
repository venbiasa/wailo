package com.venbiasa.wailo.daemon

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.time.Duration.Companion.days
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** The root as a caller needs to see it: what to show, and what to hand a user to install. */
internal class CertificateAuthorityInfo(
    val commonName: String,
    val sha256: String,
    val notAfterEpochMs: Long,
    val pem: String,
)

/**
 * Wailo's local root, and the short-lived leaves it signs for hosts the user has unlocked (ADR-0073).
 *
 * The private key never leaves the Keychain and never appears in an export: [pem] is the certificate
 * alone, which is all a trust store needs and all a user should ever be asked to install. Leaves are
 * minted on demand and cached for the process, because a handshake cannot wait on key generation and a
 * browsing session revisits the same handful of hosts.
 */
internal class WailoCertificateAuthority(private val store: CertificateAuthorityStore) {
    private val leaves = ConcurrentHashMap<String, SSLContext>()

    @Volatile
    private var root: Root? = null

    /**
     * Why there is no root, when there is none. Without it "decryption is off" and "this machine could
     * not make a key" look identical to every surface that reports the proxy's state.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * The root as it stands, without creating one. Kept separate from [ensure] so polling for status is
     * never what puts a universal signing key on a machine.
     */
    @Synchronized
    fun current(): CertificateAuthorityInfo? {
        root?.let { return it.info }
        return store.load()?.let(::restore)?.also { root = it }?.info
    }

    /** The current root, minting one on first use. Null when this machine has nowhere to keep the key. */
    @Synchronized
    fun ensure(): CertificateAuthorityInfo? {
        current()?.let { return it }
        val minted = mint()?.also { store.save(it.privateKey, it.certificate) }
        root = minted
        return minted?.info
    }

    /**
     * Replace the root. Every previously issued leaf is invalidated with it, and any machine that trusted
     * the old one has to trust the new one — which is the point of the operation, so it is never implicit.
     */
    @Synchronized
    fun rotate(): CertificateAuthorityInfo? {
        leaves.clear()
        root = null
        store.clear()
        return ensure()
    }

    /** Forget the root entirely, leaving the daemon with no way to decrypt until one is generated again. */
    @Synchronized
    fun remove() {
        leaves.clear()
        root = null
        store.clear()
    }

    /**
     * An `SSLContext` presenting a leaf for [host], or null when no root exists. Cached per host: the
     * cost is a keypair and a signature, and paying it per connection would show up as handshake latency
     * on every request of a page.
     */
    fun contextFor(host: String): SSLContext? {
        leaves[host]?.let { return it }
        val current = root ?: (ensure()?.let { root } ?: return null)
        return runCatching { leaf(current, host) }
            .getOrElse {
                lastError = "leaf for $host: ${it::class.simpleName}: ${it.message}"
                null
            }
            ?.also { leaves[host] = it }
    }

    private fun mint(): Root? = runCatching {
        val keys = generateKeys()
        val name = X500Name("CN=$ROOT_COMMON_NAME, O=Wailo, OU=Wailo Proxy")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            Date(now - CLOCK_SKEW_MS),
            Date(now + ROOT_LIFETIME_MS),
            name,
            keys.public,
        )
        val utils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keys.public))
        Root(keys.private, builder.signedBy(keys.private))
    }.getOrElse {
        lastError = "${it::class.simpleName}: ${it.message}"
        null
    }

    private fun leaf(root: Root, host: String): SSLContext {
        val keys = generateKeys()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            // From the encoded principal, not its string form: a DN round-tripped through text comes
            // back with different DER, and a chain is matched on those bytes.
            X500Name.getInstance(root.certificate.subjectX500Principal.encoded),
            BigInteger(64, SecureRandom()),
            Date(now - CLOCK_SKEW_MS),
            Date(now + LEAF_LIFETIME_MS),
            X500Name("CN=$host"),
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        // A modern client ignores the CN entirely, so the SAN is the only thing that makes this leaf
        // usable — and an IP literal has to be tagged as one rather than as a DNS name.
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(subjectAltName(host)))
        val certificate = builder.signedBy(root.privateKey)

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, EPHEMERAL_PASSWORD)
            setKeyEntry("leaf", keys.private, EPHEMERAL_PASSWORD, arrayOf(certificate, root.certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, EPHEMERAL_PASSWORD)
        }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
    }

    private fun restore(stored: StoredCertificateAuthority): Root? = runCatching {
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(stored.privateKeyPkcs8))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(stored.certificateDer)) as X509Certificate
        // An expired root would hand out leaves nothing accepts, and the failure would look like a
        // broken proxy rather than a certificate to reinstall.
        if (certificate.notAfter.time < System.currentTimeMillis()) null else Root(key, certificate)
    }.getOrNull()

    private class Root(val privateKey: PrivateKey, val certificate: X509Certificate) {
        val info: CertificateAuthorityInfo
            get() = CertificateAuthorityInfo(
                commonName = ROOT_COMMON_NAME,
                sha256 = fingerprint(certificate),
                notAfterEpochMs = certificate.notAfter.time,
                pem = pemOf(certificate),
            )
    }

    private companion object {
        const val ROOT_COMMON_NAME = "Wailo Local Root"

        /** Long enough that a user is not reinstalling it, short enough that a leaked key expires. */
        val ROOT_LIFETIME_MS = 825.days.inWholeMilliseconds

        /**
         * Apple's iOS/macOS limit for server certificates. A longer leaf is rejected outright by Safari
         * and by anything on the system trust store, so this is a hard ceiling rather than a preference.
         */
        val LEAF_LIFETIME_MS = 397.days.inWholeMilliseconds

        /** Backdated so a client whose clock runs slow does not reject a certificate minted just now. */
        val CLOCK_SKEW_MS = 1.days.inWholeMilliseconds

        val EPHEMERAL_PASSWORD = CharArray(0)

        fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        /**
         * Matched rather than resolved: `InetAddress` would go to DNS for anything that is not a
         * literal, which is a network round-trip inside a handshake.
         */
        val IP_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun subjectAltName(host: String): GeneralName {
            val literal = host.contains(':') || IP_LITERAL.matches(host)
            return GeneralName(if (literal) GeneralName.iPAddress else GeneralName.dNSName, host)
        }

        fun JcaX509v3CertificateBuilder.signedBy(key: PrivateKey): X509Certificate =
            JcaX509CertificateConverter().getCertificate(build(JcaContentSignerBuilder("SHA256withECDSA").build(key)))

        fun fingerprint(certificate: X509Certificate): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }

        fun pemOf(certificate: X509Certificate): String = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
                .let { append(it) }
            append("\n-----END CERTIFICATE-----\n")
        }
    }
}
