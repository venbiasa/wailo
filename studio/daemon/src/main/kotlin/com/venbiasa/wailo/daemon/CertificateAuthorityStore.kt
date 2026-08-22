package com.venbiasa.wailo.daemon

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class StoredCertificateAuthority(val privateKeyPkcs8: ByteArray, val certificateDer: ByteArray)

/** Where the local root's key lives. Separate from the CA so tests never touch a real Keychain. */
internal interface CertificateAuthorityStore {
    fun load(): StoredCertificateAuthority?

    fun save(privateKey: PrivateKey, certificate: X509Certificate)

    fun clear()
}

/**
 * The macOS login Keychain, via the same `security` CLI the pairing store uses (ADR-0060).
 *
 * A signing key that can mint a certificate for any host is the most dangerous secret Wailo holds, so it
 * gets the one store on this platform with an OS-enforced ACL rather than a file beside the settings.
 */
internal class KeychainCertificateAuthorityStore(
    private val service: String = "com.venbiasa.wailo.studio",
) : CertificateAuthorityStore {
    override fun load(): StoredCertificateAuthority? {
        val raw = read() ?: return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        return runCatching {
            StoredCertificateAuthority(
                privateKeyPkcs8 = Base64.getDecoder().decode(parts[0]),
                certificateDer = Base64.getDecoder().decode(parts[1]),
            )
        }.getOrNull()
    }

    override fun save(privateKey: PrivateKey, certificate: X509Certificate) {
        val encoder = Base64.getEncoder()
        write("${encoder.encodeToString(privateKey.encoded)}:${encoder.encodeToString(certificate.encoded)}")
    }

    override fun clear() {
        run("delete-generic-password", "-s", service, "-a", ACCOUNT)
    }

    private fun read(): String? = run("find-generic-password", "-s", service, "-a", ACCOUNT, "-w")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { hex -> runCatching { String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.getOrNull() }

    private fun write(value: String) {
        run(
            "add-generic-password",
            "-U",
            "-s",
            service,
            "-a",
            ACCOUNT,
            "-D",
            "Wailo proxy root",
            "-w",
            value.toByteArray().joinToString("") { "%02x".format(it) },
        )
    }

    private fun run(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("security") + arguments).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        output
    }.getOrNull()

    companion object {
        private const val ACCOUNT = "proxy-root-ca"
        private const val TIMEOUT_SECONDS = 10L

        val isSupported: Boolean
            get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) &&
                File("/usr/bin/security").canExecute()
    }
}

/**
 * A root that dies with the process, for a run with no Keychain — CI, Linux, or a scratch `WAILO_HOME`.
 * Decryption still works for the session; the user just reinstalls the root next time, which is the
 * honest trade for not writing a universal signing key to disk unprotected.
 */
internal class EphemeralCertificateAuthorityStore : CertificateAuthorityStore {
    @Volatile
    private var held: StoredCertificateAuthority? = null

    override fun load(): StoredCertificateAuthority? = held

    override fun save(privateKey: PrivateKey, certificate: X509Certificate) {
        held = StoredCertificateAuthority(privateKey.encoded, certificate.encoded)
    }

    override fun clear() {
        held = null
    }
}
