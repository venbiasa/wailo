package com.venbiasa.wailo.daemon

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import kotlin.time.Duration.Companion.days

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
     * Whether [store] has been asked yet. A miss is remembered as well as a hit, because every surface
     * polls for whether a root exists and answering that from a Keychain is a subprocess per call.
     */
    @Volatile
    private var consulted = false

    /**
     * The root as it stands, without creating one. Kept separate from [ensure] so polling for status is
     * never what puts a universal signing key on a machine.
     */
    @Synchronized
    fun current(): CertificateAuthorityInfo? {
        root?.let { return it.info }
        if (consulted) return null
        consulted = true
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
        // Ask the store again rather than trust the delete: a clear that failed has to surface as a root
        // still being there, not as a cached "none" that outlives the mistake.
        consulted = false
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
        val name = X500Principal("CN=$ROOT_COMMON_NAME, O=Wailo, OU=Wailo Proxy").encoded
        val now = System.currentTimeMillis()
        val certificate = X509.certificate(
            subject = name,
            issuer = name,
            publicKey = keys.public,
            signingKey = keys.private,
            serial = serial(),
            notBefore = Instant.ofEpochMilli(now - CLOCK_SKEW_MS),
            notAfter = Instant.ofEpochMilli(now + ROOT_LIFETIME_MS),
            extensions = listOf(
                // Depth 0: this root signs leaves and nothing that can sign further.
                X509.basicConstraints(ca = true, pathLength = 0),
                X509.keyUsage(X509.DIGITAL_SIGNATURE, X509.KEY_CERT_SIGN, X509.CRL_SIGN),
                X509.subjectKeyIdentifier(keys.public),
            ),
        )
        Root(keys.private, certificate)
    }.getOrElse {
        lastError = "${it::class.simpleName}: ${it.message}"
        null
    }

    private fun leaf(root: Root, host: String): SSLContext {
        val keys = generateKeys()
        val now = System.currentTimeMillis()
        val certificate = X509.certificate(
            subject = X500Principal("CN=$host").encoded,
            // From the encoded principal, not its string form: a DN round-tripped through text comes
            // back with different DER, and a chain is matched on those bytes.
            issuer = root.certificate.subjectX500Principal.encoded,
            publicKey = keys.public,
            signingKey = root.privateKey,
            serial = serial(),
            notBefore = Instant.ofEpochMilli(now - CLOCK_SKEW_MS),
            notAfter = Instant.ofEpochMilli(now + LEAF_LIFETIME_MS),
            extensions = listOf(
                X509.basicConstraints(ca = false),
                X509.keyUsage(X509.DIGITAL_SIGNATURE, X509.KEY_ENCIPHERMENT),
                X509.serverAuth(),
                X509.subjectAltName(host),
            ),
        )

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
        val key = KeyFactory.getInstance("EC")
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

        /** Random rather than counted: nothing here persists a counter, and a repeat is a broken chain. */
        fun serial(): BigInteger = BigInteger(64, SecureRandom())

        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
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
