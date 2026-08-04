package com.venbiasa.wailo.engine

/**
 * How a device reached the engine. The protocol semantics are identical; only which side opened the
 * underlying WebSocket differs.
 */
enum class DeviceTransport {
    LAN,
    USB,
}

/** A live SDK session after its Hello identifies the app and device. */
data class ConnectedDevice(
    val connectionId: String,
    val deviceName: String,
    val appId: String,
    val platform: String,
    val transport: DeviceTransport,
)

/**
 * One binary-message connection to an SDK. Implementations preserve WebSocket message boundaries so
 * every byte array is exactly one protocol Envelope.
 */
interface DeviceConnection {
    val id: String
    val transport: DeviceTransport

    /**
     * Whether the peer is reachable only from this machine — loopback, or a USB tunnel that terminates
     * on it. Those need no pairing: the address itself is the proof, and requiring a key would break
     * the zero-config Simulator and `adb reverse` paths for nothing. Anything arriving over WiFi is
     * whoever answered an mDNS advertisement, and has to authenticate (ADR-0039).
     */
    val isTrusted: Boolean

    suspend fun receive(): ByteArray?
    suspend fun send(bytes: ByteArray)
    fun close()
}
