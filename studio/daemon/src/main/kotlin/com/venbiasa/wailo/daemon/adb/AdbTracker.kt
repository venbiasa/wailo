package com.venbiasa.wailo.daemon.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the adb server's pushed device list. The CLI fallback in [AdbClient] starts the server when
 * needed; once it exists this parked socket avoids polling a subprocess forever.
 */
internal class AdbTracker(private val port: Int = defaultServerPort()) {
    suspend fun stream(onChange: suspend (List<AdbDevice>) -> Unit) {
        if (consume(SERVICE_LONG, onChange)) return
        if (consume(SERVICE_SHORT, onChange)) return
        throw IOException("The adb server on port $port rejected host:track-devices.")
    }

    private suspend fun consume(
        service: String,
        onChange: suspend (List<AdbDevice>) -> Unit,
    ): Boolean = coroutineScope {
        val socket = Socket()
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(LOOPBACK_HOST, port), CONNECT_TIMEOUT_MS)
                val input = socket.getInputStream()
                socket.getOutputStream().request(service)
                if (!input.accepted()) return@withContext false
                var payload = input.readFrame()
                while (payload != null) {
                    onChange(parseDevices(payload))
                    payload = input.readFrame()
                }
                true
            }
        } catch (broken: IOException) {
            currentCoroutineContext().ensureActive()
            throw broken
        } finally {
            closer.cancel()
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val SERVICE_LONG = "host:track-devices-l"
        private const val SERVICE_SHORT = "host:track-devices"

        fun defaultServerPort(): Int =
            System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037
    }
}

private const val OKAY = "OKAY"
private const val FAIL = "FAIL"

private fun OutputStream.request(service: String) {
    write("%04x%s".format(service.length, service).toByteArray(Charsets.US_ASCII))
    flush()
}

private fun InputStream.accepted(): Boolean = when (val status = readAscii(OKAY.length)) {
    OKAY -> true
    FAIL -> false
    null -> throw IOException("The adb server closed the connection without answering.")
    else -> throw IOException("The adb server answered '$status'.")
}

private fun InputStream.readFrame(): String? {
    val header = readAscii(4) ?: return null
    val length = header.toIntOrNull(16) ?: throw IOException("The adb server framed a list as '$header'.")
    if (length == 0) return ""
    return readAscii(length) ?: throw IOException("The adb server cut a $length byte list short.")
}

private fun InputStream.readAscii(count: Int): String? {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) return null
        filled += read
    }
    return String(buffer, Charsets.US_ASCII)
}
