package com.venbiasa.wailo.sdk.android

import java.net.URI

/**
 * A desktop address known to be diallable: a host legal inside a URL authority, plus the port the text
 * named (null when it named none, leaving the choice to the caller).
 *
 * Every address arrives as free text — the on-device panel's field, a literal passed to `Wailo.start`,
 * an intent extra — so this is the one gate in front of persistence and the transport. Typing an IP
 * together with its port into a field labelled "address" is the input that has to be caught: on iOS it
 * produced `ws://10.0.0.2:8080:8899/` and crashed the host app, and because the text was stored before
 * it was ever dialled, the crash then repeated on every launch (ADR-0035). Text that names no diallable
 * address is refused here rather than saved and left to look like "the desktop isn't running".
 *
 * Forgiving about shape, strict about legality. `host:port`, a bare IPv6 literal, and a pasted
 * `ws://host:port/` all resolve, because each is a plausible thing to type and none is ambiguous.
 */
class WailoAddress private constructor(
    /** Ready to drop into a URL authority — an IPv6 literal keeps the brackets it needs there. */
    val host: String,
    /** The port the text named, or null for "whatever the caller resolves to". */
    val port: Int?,
) {

    /** Canonical `host` or `host:port`, which re-parses to the same address. */
    override fun toString(): String = port?.let { "$host:$it" } ?: host

    override fun equals(other: Any?): Boolean =
        other is WailoAddress && other.host == host && other.port == port

    override fun hashCode(): Int = host.hashCode() * 31 + (port ?: 0)

    companion object {
        val PORT_RANGE = 1..65_535

        fun parse(text: String): WailoAddress? {
            val (host, portText) = split(authority(text)) ?: return null
            val port = portText?.let { it.toIntOrNull()?.takeIf(PORT_RANGE::contains) ?: return null }
            if (!isDiallable(host)) return null
            return WailoAddress(host, port)
        }

        /**
         * Reduces a pasted address to its `host[:port]` authority. Addresses get copied from somewhere —
         * Studio prints its own as a URL — so a scheme and a trailing slash are decoration, not intent.
         */
        private fun authority(text: String): String {
            var authority = text.trim()
            val scheme = authority.indexOf("://")
            if (scheme >= 0) authority = authority.substring(scheme + 3)
            return authority.trimEnd('/')
        }

        private fun split(authority: String): Pair<String, String?>? {
            if (authority.isEmpty()) return null

            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val host = authority.substring(0, close + 1)
                val rest = authority.substring(close + 1)
                if (rest.isEmpty()) return host to null
                if (!rest.startsWith(":")) return null
                return host to rest.substring(1)
            }

            val parts = authority.split(":")
            return when (parts.size) {
                1 -> authority to null
                2 -> parts[0] to parts[1]
                // Several colons and no brackets reads as an IPv6 literal typed bare. Bracketing is what
                // makes it a legal authority, and a port could not have been expressed without them —
                // the literal check below is what rejects the other reading of the same text, a doubled
                // port such as `10.0.0.2:8080:9000`.
                else -> "[$authority]" to null
            }
        }

        private const val ILLEGAL_IN_HOST = "/?#@[]\\"

        private fun isDiallable(host: String): Boolean {
            if (host.startsWith("[")) return host.endsWith("]") && isIpv6Literal(host)
            return host.isNotEmpty() && host.none { it.isWhitespace() || it in ILLEGAL_IN_HOST }
        }

        /**
         * `URI` only hands back a host for a bracketed authority when what is inside is a real IPv6
         * literal, so asking it is the check — rolling our own parser for a form this exact would be
         * a second, worse implementation of RFC 2732.
         */
        private fun isIpv6Literal(bracketed: String): Boolean =
            runCatching { URI("ws://$bracketed:1/").host }.getOrNull() == bracketed
    }
}
