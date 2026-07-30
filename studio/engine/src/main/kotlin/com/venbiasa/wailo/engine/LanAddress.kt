package com.venbiasa.wailo.engine

import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The host's primary LAN IPv4 — the address a device on the same network dials to reach the capture
 * server. We ask the OS which local interface routes toward a public IP via a UDP "connect" (which
 * sends nothing), so we get the active outbound interface, instead of the first
 * enumerated site-local address — which is often a VPN/Docker/utun/bridge IP. Falls back to
 * "localhost" when there is no route (fully offline), which still covers the emulator/simulator case.
 */
fun resolveLanAddress(): String = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("8.8.8.8"), 53)
        socket.localAddress?.hostAddress
    }
}.getOrNull()?.takeUnless { it.isBlank() || it == "0.0.0.0" } ?: "localhost"
