package com.venbiasa.wailo.shared

internal fun FlowEntry.isLockedProxyTunnel(): Boolean =
    viaProxy &&
        exchange.request?.method.equals("CONNECT", ignoreCase = true) &&
        exchange.response?.code?.let { it in 200..299 } == true

internal fun List<String>.toggleExactHost(host: String): List<String> =
    if (host in this) filterNot { it == host } else this + host
