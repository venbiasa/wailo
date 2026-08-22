package com.venbiasa.wailo.shared

/**
 * The bundled proxy as the viewer sees it (ADR-0070).
 *
 * Mirrors the daemon's `ProxyStatus` by value for the same reason [FlowEntry] mirrors the engine's row:
 * `shared` is a sibling of the modules that own this state, not a consumer of them, so the host maps it
 * across at the `desktopApp` boundary.
 *
 * [error] is the daemon's verdict on the last start attempt — a port something else already holds is the
 * usual one — and is the only thing that can explain a switch that flips itself back off.
 */
data class ProxyState(
    val running: Boolean = false,
    val port: Int = 9090,
    val connections: Int = 0,
    val exchanges: Long = 0,
    val error: String? = null,
    /** Bound beyond loopback, which also makes it an open relay while on (ADR-0074). */
    val lan: Boolean = false,
    val lanAddress: String = "",
    /** Whether this machine's own network settings currently point at Wailo (ADR-0075). */
    val systemProxy: Boolean = false,
    val systemProxySupported: Boolean = false,
    /** The proxy that was already configured here and is now forwarded through, if there was one. */
    val chainedTo: String = "",
    /** Whether a local root exists — the first of the two things decryption needs (ADR-0071). */
    val caInstalled: Boolean = false,
    val caFingerprint: String = "",
    /** Host patterns the user unlocked. Everything else stays an opaque tunnel. */
    val decryptHosts: List<String> = emptyList(),
    /**
     * What the last certificate action did in the user's terms — where the file landed, or why it did
     * not. Writing a file somewhere the user cannot see is indistinguishable from doing nothing, and the
     * host is the only one that knows the path.
     */
    val certificateNotice: String = "",
) {
    val address: String get() = "${if (lan && lanAddress.isNotEmpty()) lanAddress else "127.0.0.1"}:$port"
}

/**
 * What Studio can ask the daemon to do about the proxy's setup. One type rather than a parameter each,
 * because these arrive together and every one of them is a round trip whose answer is a new [ProxyState].
 */
sealed interface ProxySetupAction {
    data class SetLan(val enabled: Boolean) : ProxySetupAction

    data class SetSystemProxy(val enabled: Boolean) : ProxySetupAction

    /** Mint the root if there is not one, and hand back the PEM to install. */
    data object InstallCertificate : ProxySetupAction

    data object RotateCertificate : ProxySetupAction

    data object RemoveCertificate : ProxySetupAction

    /** Replace the unlocked hosts wholesale, so revoking is the same call as granting. */
    data class SetDecryptHosts(val hosts: List<String>) : ProxySetupAction
}
