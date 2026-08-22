package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.proxy.ProxySetup
import okio.ByteString.Companion.toByteString

/**
 * What Wailo serves to a browser pointed *at* the proxy port (ADR-0076): the local root, and the steps
 * for the device it is being installed on.
 *
 * This exists because a phone cannot read a file on the desktop. Every other way to get the certificate
 * across — AirDrop, email, a USB cable, a QR of a 1 KB PEM — is worse than fetching it from the machine
 * the device is already routed through.
 *
 * It never mints. [root] is the existing root or nothing, so a device that reaches this page cannot be
 * what puts a universal signing key on the user's machine (ADR-0073); creating one stays an act at the
 * desk, in Studio or the CLI.
 */
internal class ProxySetupPage(
    private val root: () -> CertificateAuthorityInfo?,
    private val decryptHosts: () -> List<String>,
) : ProxySetup {
    override fun page(target: String): HttpResponse? = when (target.substringBefore('?')) {
        "/", "/setup" -> html(landing())
        CERTIFICATE_PATH -> root()?.let { certificate(it) } ?: html(landing())
        else -> null
    }

    private fun landing(): String {
        val current = root()
        val unlocked = decryptHosts()
        return buildString {
            append("<h1>Wailo</h1>")
            append("<p>This device is talking to the Wailo proxy, so its traffic is already being ")
            append("captured. HTTPS stays encrypted until the two steps below are both done.</p>")
            if (current == null) {
                append("<h2>1. No certificate yet</h2>")
                append("<p>Open Wailo on the computer and create one under Settings &rarr; Proxy, then ")
                append("reload this page.</p>")
            } else {
                append("<h2>1. Install the certificate</h2>")
                append("<p><a class=\"button\" href=\"$CERTIFICATE_PATH\">Download Wailo's certificate</a></p>")
                append("<p class=\"fingerprint\">${current.sha256.escaped()}</p>")
                append("<p><strong>iPhone or iPad:</strong> the profile downloads, then Settings &rarr; ")
                append("Profile Downloaded &rarr; Install. Trust it under Settings &rarr; General &rarr; ")
                append("About &rarr; Certificate Trust Settings — the download alone does nothing.</p>")
                append("<p><strong>Android:</strong> Settings &rarr; Security &rarr; Encryption &amp; ")
                append("credentials &rarr; Install a certificate &rarr; CA certificate. Apps only trust ")
                append("user certificates if they opt in, so a release build may still refuse.</p>")
            }
            append("<h2>2. Unlock the hosts to read</h2>")
            if (unlocked.isEmpty()) {
                append("<p>Nothing is unlocked, so every HTTPS connection is tunnelled without being ")
                append("read. Add the hosts you are debugging in Settings &rarr; Proxy on the computer.</p>")
            } else {
                append("<p>Currently readable: ")
                append(unlocked.joinToString(", ") { "<code>${it.escaped()}</code>" })
                append(". Everything else is tunnelled without being read.</p>")
            }
        }
    }

    /**
     * `application/x-x509-ca-cert` is what makes iOS treat this as a profile to install and Android open
     * its certificate installer. Served as `text/plain` it is a file both platforms will happily download
     * and then do nothing with.
     */
    private fun certificate(current: CertificateAuthorityInfo) = HttpResponse(
        code = 200,
        message = "OK",
        headers = listOf(
            Header("Content-Type", "application/x-x509-ca-cert"),
            Header("Content-Disposition", "attachment; filename=\"wailo-root.pem\""),
            Header("Cache-Control", "no-store"),
        ),
        body = current.pem.toByteArray().toByteString(),
        body_size = current.pem.toByteArray().size.toLong(),
    )

    private fun html(body: String): HttpResponse {
        val bytes = (PAGE_HEAD + body + PAGE_TAIL).toByteArray()
        return HttpResponse(
            code = 200,
            message = "OK",
            headers = listOf(
                Header("Content-Type", "text/html; charset=utf-8"),
                Header("Cache-Control", "no-store"),
            ),
            body = bytes.toByteString(),
            body_size = bytes.size.toLong(),
        )
    }

    private companion object {
        const val CERTIFICATE_PATH = "/cert"

        // Deliberately one file with no assets: every request for one would be another round trip through
        // a proxy the device may not be able to use yet.
        val PAGE_HEAD = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Wailo</title><style>
            :root { color-scheme: light dark; --ink: #111; --muted: #666; --line: #ddd; --bg: #fff; }
            @media (prefers-color-scheme: dark) {
              :root { --ink: #f2f2f2; --muted: #a0a0a0; --line: #333; --bg: #111; }
            }
            body { margin: 0 auto; padding: 24px; max-width: 34rem; background: var(--bg); color: var(--ink);
              font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
            h1 { font-size: 1.5rem; margin: 0 0 1rem; }
            h2 { font-size: 1rem; margin: 2rem 0 .5rem; }
            a.button { display: inline-block; padding: .7rem 1.1rem; border: 1px solid var(--ink);
              border-radius: 8px; color: var(--ink); text-decoration: none; }
            code, .fingerprint { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .8rem; }
            .fingerprint { color: var(--muted); word-break: break-all; }
            p { margin: .6rem 0; } strong { font-weight: 600; }
            </style></head><body>
        """.trimIndent()

        const val PAGE_TAIL = "</body></html>"
    }
}

private fun String.escaped(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
