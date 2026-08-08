package com.venbiasa.wailo.desktop.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable

internal enum class AdbStatus {
    /** Plugged in, but not answering: the screen is locked, or the debugging prompt is unanswered. */
    UNAUTHORIZED,
    FORWARDING,
    WAITING_FOR_APP,
    CONNECTED,
    ERROR,
}

internal data class AdbDeviceState(
    val serial: String,
    val name: String,
    val status: AdbStatus,
    val error: String? = null,
)

/**
 * Keeps an `adb reverse` mapping alive on every attached Android device, so the desktop is reachable at
 * `localhost` inside the app without anyone typing a command.
 *
 * This is the counterpart of [com.venbiasa.wailo.desktop.usb.UsbDeviceManager], and deliberately not
 * shaped like it. usbmux has Studio dial *into* the device, so it owns a `DeviceConnection` and calls
 * `engine.attach`. `adb reverse` only installs a route: the device stays the WebSocket client and
 * arrives on the ordinary capture server, on loopback, needing no pairing. So there is nothing to
 * attach here — the work is keeping the route in step with what is plugged in, and saying so.
 *
 * What is plugged in arrives pushed, from [AdbTracker]. Polling is kept as the fallback, because the
 * push needs an adb server already running and `adb devices` is what starts one.
 */
internal class AdbDeviceManager(
    hostPort: Int,
    private val client: AdbClient = AdbClient(),
    private val tracker: AdbTracker = AdbTracker(),
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<AdbDeviceState>>(emptyList())
    val devices: StateFlow<List<AdbDeviceState>> = _devices.asStateFlow()

    private val _hostPort = MutableStateFlow(hostPort)

    /** What adb last said was plugged in, before this class has any opinion about it. */
    private val attached = MutableStateFlow<List<AdbDevice>>(emptyList())

    /** Adb itself being unreachable, which is a different thing to nothing being plugged in. */
    private val failure = MutableStateFlow<String?>(null)

    /** Whether `adb` was found at all. False leaves the panel explaining itself instead of silent. */
    val supported: Boolean = client.available

    /** Where `adb` was found, so "it isn't working" has somewhere to start. */
    val executablePath: String? = client.path

    /** The reverse mapping installed per serial, so a port change tears the old one down. */
    private val installed = mutableMapOf<String, Int>()

    private val jobs = mutableListOf<Job>()

    init {
        if (supported) {
            jobs += scope.launch { watchLoop() }
            jobs += scope.launch { reconcileLoop() }
        }
    }

    /**
     * Point the tunnels at a new capture port.
     *
     * The device-side port moves with it. Keeping them equal is what makes the SDK's zero-config
     * default work: it dials `localhost` on the capture port, and has no way to be told about a
     * different one on the device side.
     */
    fun setHostPort(port: Int) {
        _hostPort.value = port
    }

    /**
     * Keeps a tracking connection to the adb server up, and falls back to asking when there is none.
     *
     * The fallback doubles as the bootstrap: nothing can be tracked before an adb server exists, and
     * `adb devices` is what brings one up. It also covers an adb too old to track at all, which then
     * behaves exactly as the old poll loop did, at the same interval.
     */
    private suspend fun watchLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                tracker.stream { devices ->
                    attached.value = devices
                    failure.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                poll()
            }
            // Also the pause after a connection ends, so an adb server going up and down cannot spin us.
            delay(RETRY_INTERVAL_MS)
        }
    }

    private suspend fun poll() {
        try {
            attached.value = runInterruptible { client.devices() }
            failure.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // adb itself is broken or gone; say so rather than clearing the list silently.
            failure.value = error.message ?: "adb is not responding."
        }
    }

    /**
     * The only writer of [devices] and the only toucher of [installed], which is what keeps the reverse
     * mappings consistent without a lock.
     *
     * [_hostPort] is folded in rather than read, because with a pushed device list nothing else would
     * make a port change take effect — no device event follows someone editing the field in Settings.
     */
    private suspend fun reconcileLoop() {
        combine(attached, _hostPort, failure, ::Triple).collect { (devices, port, error) ->
            if (error != null) {
                _devices.value = listOf(AdbDeviceState("adb", "adb", AdbStatus.ERROR, error))
                return@collect
            }
            for (gone in installed.keys - devices.map(AdbDevice::serial).toSet()) {
                installed.remove(gone)
            }
            _devices.value = devices.map { device -> reconcile(device, port) }.sortedBy { it.name.lowercase() }
        }
    }

    private suspend fun reconcile(device: AdbDevice, port: Int): AdbDeviceState {
        if (!device.ready) {
            installed.remove(device.serial)
            return AdbDeviceState(
                serial = device.serial,
                name = device.label,
                status = if (device.state == UNAUTHORIZED) AdbStatus.UNAUTHORIZED else AdbStatus.ERROR,
                error = when (device.state) {
                    UNAUTHORIZED -> "Unlock the device and allow USB debugging for this computer."
                    else -> "adb reports it as ${device.state}."
                },
            )
        }

        if (installed[device.serial] == port) {
            return AdbDeviceState(device.serial, device.label, AdbStatus.WAITING_FOR_APP)
        }

        installed.remove(device.serial)?.let { stale ->
            runInterruptible { client.removeReverse(device.serial, stale) }
        }
        return try {
            runInterruptible { client.reverse(device.serial, devicePort = port, hostPort = port) }
            installed[device.serial] = port
            AdbDeviceState(device.serial, device.label, AdbStatus.FORWARDING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AdbDeviceState(device.serial, device.label, AdbStatus.ERROR, error.message)
        }
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        // Left in place on purpose. A reverse mapping costs the device nothing, and tearing it down on
        // quit would break the common habit of leaving an app running and reopening Studio.
        installed.clear()
        scope.cancel()
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 2000L
        const val UNAUTHORIZED = "unauthorized"
    }
}
