package com.venbiasa.wailo.daemon.adb

import com.venbiasa.wailo.daemon.AdbConnectionStatus
import com.venbiasa.wailo.daemon.AdbController
import com.venbiasa.wailo.daemon.AdbDeviceInfo
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Keeps adb reverse installed while the daemon lives, independent of whether Studio or MCP is the
 * frontend currently attached.
 */
internal class AdbDeviceManager(
    hostPort: Int,
    private val client: AdbClient = AdbClient(),
    private val tracker: AdbTracker = AdbTracker(),
) : AdbController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices = MutableStateFlow<List<AdbDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<AdbDeviceInfo>> = _devices
    private val hostPort = MutableStateFlow(hostPort)
    private val attached = MutableStateFlow<List<AdbDevice>>(emptyList())
    private val failure = MutableStateFlow<String?>(null)
    override val supported: Boolean = client.available
    override val executablePath: String? = client.path
    private val installed = mutableMapOf<String, Int>()
    private val jobs = mutableListOf<Job>()

    init {
        if (supported) {
            jobs += scope.launch { watchLoop() }
            jobs += scope.launch { reconcileLoop() }
        }
    }

    override fun setHostPort(port: Int) {
        hostPort.value = port
    }

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
            failure.value = error.message ?: "adb is not responding."
        }
    }

    private suspend fun reconcileLoop() {
        combine(attached, hostPort, failure, ::Triple).collect { (devices, port, error) ->
            if (error != null) {
                _devices.value = listOf(
                    AdbDeviceInfo("adb", "adb", AdbConnectionStatus.ERROR, error),
                )
                return@collect
            }
            for (gone in installed.keys - devices.map(AdbDevice::serial).toSet()) {
                installed.remove(gone)
            }
            _devices.value = devices.map { device -> reconcile(device, port) }
                .sortedBy { it.name.lowercase() }
        }
    }

    private suspend fun reconcile(device: AdbDevice, port: Int): AdbDeviceInfo {
        if (!device.ready) {
            installed.remove(device.serial)
            return AdbDeviceInfo(
                serial = device.serial,
                name = device.label,
                status = if (device.state == UNAUTHORIZED) {
                    AdbConnectionStatus.UNAUTHORIZED
                } else {
                    AdbConnectionStatus.ERROR
                },
                error = when (device.state) {
                    UNAUTHORIZED -> "Unlock the device and allow USB debugging for this computer."
                    else -> "adb reports it as ${device.state}."
                },
            )
        }

        if (installed[device.serial] == port) {
            return AdbDeviceInfo(device.serial, device.label, AdbConnectionStatus.WAITING_FOR_APP)
        }

        installed.remove(device.serial)?.let { stale ->
            runInterruptible { client.removeReverse(device.serial, stale) }
        }
        return try {
            runInterruptible { client.reverse(device.serial, devicePort = port, hostPort = port) }
            installed[device.serial] = port
            AdbDeviceInfo(device.serial, device.label, AdbConnectionStatus.FORWARDING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AdbDeviceInfo(device.serial, device.label, AdbConnectionStatus.ERROR, error.message)
        }
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        installed.clear()
        scope.cancel()
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 2000L
        const val UNAUTHORIZED = "unauthorized"
    }
}
