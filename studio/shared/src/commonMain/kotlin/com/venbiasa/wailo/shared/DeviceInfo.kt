package com.venbiasa.wailo.shared

enum class DeviceTransportKind {
    LAN,
    USB,
}

enum class DeviceConnectionStatus {
    ATTACHED,
    CONNECTING,
    WAITING_FOR_APP,
    CONNECTED,
    ERROR,
}

/** Host-owned connection state rendered by the Devices tool panel. */
data class DeviceInfo(
    val id: String,
    val name: String,
    val appId: String?,
    val platform: String,
    val transport: DeviceTransportKind,
    val status: DeviceConnectionStatus,
    val detail: String? = null,
    val error: String? = null,
)
