package com.venbiasa.wailo.shared

enum class DeviceTransportKind {
    LAN,

    /** iOS over the cable, through usbmux. Studio dials the device. */
    USB,

    /** Android over the cable, through an `adb reverse` route. The device dials Studio. */
    ADB,
}

enum class DeviceConnectionStatus {
    ATTACHED,
    CONNECTING,
    WAITING_FOR_APP,

    /** Plugged in, but not answering until someone unlocks it and allows this computer. */
    UNAUTHORIZED,
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
