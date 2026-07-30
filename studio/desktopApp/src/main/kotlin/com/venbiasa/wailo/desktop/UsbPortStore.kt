package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.desktop.usb.UsbDeviceManager
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.settings.createKeyValueStore

/**
 * Persists the device-side port Studio dials over USB, mirroring [PortStore].
 *
 * Separate from the LAN port because it names a port on the *phone*, not one this app binds. usbmux
 * forwards to a number and advertises nothing, so the two ends can only agree by both being set to the
 * same value — which is exactly why it has to be settable here as well as in the SDK.
 */
object UsbPortStore {
    private const val KEY = "usbDevicePort"
    private val store = createKeyValueStore("desktop")

    fun load(): Int = store.getInt(KEY, UsbDeviceManager.DEFAULT_DEVICE_PORT)
        .takeIf { it in WailoEngine.PORT_RANGE } ?: UsbDeviceManager.DEFAULT_DEVICE_PORT

    fun save(port: Int) = store.putInt(KEY, port)
}
