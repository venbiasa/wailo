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
) {
    val address: String get() = "127.0.0.1:$port"
}
