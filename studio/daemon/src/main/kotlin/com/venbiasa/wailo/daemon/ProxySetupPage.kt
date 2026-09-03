package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.proxy.ProxySetup
import java.net.URI
import okio.ByteString.Companion.toByteString

const val PROXY_SETUP_HOST = "wailo.test"

/**
 * Serves device setup and the existing public root through the proxy (ADR-0076).
 * Network requests never mint a signing key.
 */
internal class ProxySetupPage(
    private val root: () -> CertificateAuthorityInfo?,
    private val decryptHosts: () -> List<String>,
) : ProxySetup {
    override fun page(target: String): HttpResponse? {
        val absolute = runCatching { URI(target) }.getOrNull()
        if (absolute?.host.equals(PROXY_SETUP_HOST, ignoreCase = true)) {
            return when {
                absolute?.scheme.equals("http", ignoreCase = true) &&
                    absolute?.path == ROUTE_CHECK_PATH -> routeCheck()
                absolute?.scheme.equals("http", ignoreCase = true) &&
                    absolute?.path in setOf("", "/", "/setup") -> html(landing())
                absolute?.scheme.equals("http", ignoreCase = true) &&
                    absolute?.path == CERTIFICATE_PATH -> root()?.let(::certificate) ?: html(landing())
                absolute?.scheme.equals("https", ignoreCase = true) &&
                    absolute?.path == TRUST_CHECK_PATH -> root()?.let(::trustCheck)
                else -> null
            }
        }
        return when (target.substringBefore('?')) {
            "/", "/setup" -> html(landing())
            CERTIFICATE_PATH -> root()?.let { certificate(it) } ?: html(landing())
            else -> null
        }
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
                append("<p><button class=\"button\" onclick=\"checkTrust()\">Check browser trust</button></p>")
                append("<p id=\"trust\" class=\"fingerprint\">Not checked yet.</p>")
                append("<script>async function checkTrust(){const status=document.getElementById('trust');")
                append("status.textContent='Checking…';try{const response=await fetch('https://")
                append(PROXY_SETUP_HOST)
                append(TRUST_CHECK_PATH)
                append("',{cache:'no-store'});status.textContent=response.ok?")
                append("'Confirmed: this browser trusts Wailo.':'The check was refused.';}catch(_){")
                append("status.textContent='Not trusted yet. Finish the install and trust steps, then retry.';}}</script>")
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

    // This MIME type opens the platform certificate installer instead of downloading inert text.
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

    private fun trustCheck(current: CertificateAuthorityInfo): HttpResponse {
        val bytes = "trusted\nsha256=${current.sha256}\n".toByteArray()
        return HttpResponse(
            code = 200,
            message = "OK",
            headers = listOf(
                Header("Content-Type", "text/plain; charset=utf-8"),
                Header("Access-Control-Allow-Origin", "*"),
                Header("Cache-Control", "no-store"),
            ),
            body = bytes.toByteString(),
            body_size = bytes.size.toLong(),
        )
    }

    private fun routeCheck(): HttpResponse {
        val bytes = "routed\n".toByteArray()
        return HttpResponse(
            code = 200,
            message = "OK",
            headers = listOf(
                Header("Content-Type", "text/plain; charset=utf-8"),
                Header("Cache-Control", "no-store"),
            ),
            body = bytes.toByteString(),
            body_size = bytes.size.toLong(),
        )
    }

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
        const val ROUTE_CHECK_PATH = "/route-check"
        const val TRUST_CHECK_PATH = "/check"

        // One asset avoids extra requests through a proxy that may not work yet.
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
            button.button { padding: .7rem 1.1rem; border: 1px solid var(--ink); border-radius: 8px;
              color: var(--ink); background: var(--bg); font: inherit; }
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
