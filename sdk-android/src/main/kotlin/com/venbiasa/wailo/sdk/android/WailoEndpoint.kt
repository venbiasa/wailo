package com.venbiasa.wailo.sdk.android

import java.net.InetAddress

/** Where the SDK will dial next, and the identity it expects to find there (null when it has none). */
internal data class WailoEndpoint(val host: String, val port: Int, val studioId: String?) {

    override fun toString(): String = "$host:$port"

    /**
     * Whether the engine will see this peer as loopback and waive the handshake, which it decides with
     * `InetAddress.getByName(remote).isLoopbackAddress` (`WailoEngine.isLoopbackPeer`). Asking the same
     * question the same way is what keeps the two ends from disagreeing about whether a session starts
     * with `AuthClientHelloV3` or `Hello` — a disagreement that reads as a connection which opens and
     * then goes silent.
     *
     * Resolution, not string matching, because the engine resolves too: `adb reverse` arrives as
     * `localhost`, but a host file alias for 127.0.0.1 has to reach the same verdict. Called from the
     * connect loop, off the main thread, immediately before a connect that resolves the same name.
     */
    fun isLoopback(): Boolean = runCatching {
        InetAddress.getByName(host.removePrefix("[").removeSuffix("]")).isLoopbackAddress
    }.getOrDefault(false)
}

/**
 * Picks the desktop to dial, re-evaluated on every connect attempt so a change takes effect without
 * rebuilding anything. The Android counterpart to iOS's `WailoCoordinator` (ADR-0035), with the same
 * four levels, highest first:
 *
 * 1. the host passed to [Wailo.webSocketSink] / `Wailo.start`
 * 2. the override the user pinned in the panel ([WailoHostStore])
 * 3. a discovered desktop — **only** one this device already holds a key for (ADR-0040)
 * 4. `localhost`, which is where `adb reverse` puts the desktop and why Android needed none of this
 *    until WiFi became an option
 *
 * Discovery sits below the two explicit levels so an address the user named is never silently
 * overridden by some other Mac that happens to be advertising, and it may only *re*-connect: reaching
 * a desktop for the first time is always a deliberate act (typing its address, or scanning its QR).
 */
internal object WailoEndpointResolver {

    const val LOOPBACK: String = "localhost"

    fun resolve(
        explicitHost: String?,
        explicitPort: Int?,
        discovered: List<WailoDesktop>,
    ): WailoEndpoint {
        val explicit = explicitHost?.let(WailoAddress::parse)
        if (explicit != null) {
            return WailoEndpoint(explicit.host, port(explicitPort, explicit.port, null), studioId = null)
        }

        val pinned = WailoHostStore.host
        if (pinned != null) {
            return WailoEndpoint(
                pinned,
                port(explicitPort, null, null),
                studioId = WailoHostStore.expectedStudioId,
            )
        }

        val trusted = firstTrusted(discovered)
        if (trusted != null) {
            return WailoEndpoint(trusted.host, port(explicitPort, null, trusted.port), trusted.studioId)
        }

        return WailoEndpoint(LOOPBACK, port(explicitPort, null, null), studioId = null)
    }

    /**
     * The one discovered desktop this device may dial unprompted: advertising a `sid` it holds a
     * live key for.
     *
     * That restriction is ADR-0040's original fix. A colleague's Studio on the same WiFi advertises a
     * `sid` this device knows nothing about, and used to win simply because its hostname sorted first.
     * Reaching a *new* desktop is now always deliberate — typing its address, or scanning its QR — and
     * discovery's job is only to find one already trusted, wherever DHCP has moved it.
     */
    fun firstTrusted(discovered: List<WailoDesktop>): WailoDesktop? = discovered.firstOrNull { desktop ->
        desktop.studioId.isNotEmpty() && WailoPairingStore.pairing(desktop.studioId)?.refused == false
    }

    /**
     * Which Studio is at [host], as far as this device can tell: the `sid` advertised there, else the
     * one last reached there, else the only one there is.
     *
     * A typed IP names a machine, not an identity, so something has to work back to one — otherwise a
     * desktop that moved networks is met as a stranger and pairs a second time. The single-pairing
     * fallback covers the normal case, one developer with one Mac, and costs nothing when wrong: the
     * handshake simply fails against a desktop that is genuinely different, which is a refusal to
     * re-pair, not a security hole.
     */
    fun pairingFor(host: String, studioId: String?, discovered: List<WailoDesktop> = emptyList()): WailoPairing? {
        val advertised = studioId
            ?: discovered.firstOrNull { it.host == host && it.studioId.isNotEmpty() }?.studioId
        advertised?.let(WailoPairingStore::pairing)?.let { return it }
        val all = WailoPairingStore.all()
        return all.firstOrNull { it.lastHost == host } ?: all.singleOrNull()
    }

    private fun port(explicit: Int?, fromAddress: Int?, discovered: Int?): Int =
        explicit ?: fromAddress ?: WailoHostStore.port ?: discovered ?: WailoClient.DEFAULT_PORT
}
