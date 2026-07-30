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

    suspend fun receive(): ByteArray?
    suspend fun send(bytes: ByteArray)
    fun close()
}
