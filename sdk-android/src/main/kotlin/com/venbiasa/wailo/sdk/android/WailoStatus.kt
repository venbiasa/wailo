package com.venbiasa.wailo.sdk.android

/**
 * A remembered address now answered by a different identity (ADR-0040). Surfaced rather than acted on:
 * this is either a machine that changed hands or someone standing in the path, and from the device's
 * side the two are indistinguishable.
 */
data class WailoIdentityChange(
    val host: String,
    /** The `sid` this device pinned when it first trusted this address. */
    val expected: String,
    val actual: String,
)

/**
 * Everything the on-device panel draws, in one value so a Compose screen needs a single `collectAsState`
 * and can never render a half-updated mix of two flows — a connected badge next to the address it was
 * connected to a moment ago.
 */
data class WailoStatus(
    val connected: Boolean = false,
    /** `host:port` currently dialled, or null while nothing is being dialled at all. */
    val activeAddress: String? = null,
    /**
     * This link skipped the handshake because the peer resolved to loopback — `adb reverse` or the
     * emulator, where the kernel already guarantees the desktop is this machine.
     *
     * Reported by the connect loop rather than re-derived from [activeAddress], which cannot be done
     * without repeating [WailoEndpoint.isLoopback]'s name resolution. A panel that guessed differently
     * would tell someone their traffic is authenticated when it is not.
     */
    val handshakeWaived: Boolean = false,
    val configuredHost: String? = null,
    val configuredPort: Int? = null,
    val discovered: List<WailoDesktop> = emptyList(),
    val pairings: List<WailoPairing> = emptyList(),
    /**
     * Studio said it does not know this device. Latched until a human clears it: `AuthResult` is
     * unauthenticated, so retrying on a timer would let anyone park the device in a re-pair prompt.
     */
    val refusal: String? = null,
    val identityChange: WailoIdentityChange? = null,
)
