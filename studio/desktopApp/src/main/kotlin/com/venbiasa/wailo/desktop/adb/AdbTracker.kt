package com.venbiasa.wailo.desktop.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Watches the adb server's device list over its own socket, so a phone being plugged in is an event
 * rather than something Studio finds out about on its next tick.
 *
 * `adb devices` is itself a thin client over this server, so polling it means forking a process every
 * couple of seconds, forever, to be told nothing changed — measured at ~14ms of CPU a go, which is
 * nothing in isolation and a timer that never sleeps on a laptop. `host:track-devices` asks once and is
 * answered on every change: a parked read, no wakeups, and no delay before a device shows up.
 *
 * The framing is four ASCII hex digits of length then that many bytes — for the request, and again for
 * each pushed list (`0000` when nothing is attached). The payload is what `adb devices -l` prints minus
 * the header, so [parseDevices] reads it unchanged.
 */
internal class AdbTracker(private val port: Int = defaultServerPort()) {

    /**
     * Delivers the device list on every change until the connection ends, then returns.
     *
     * Returning is not failure: the adb server gets killed, upgraded and restarted often enough that
     * the caller is expected to reconnect. Throwing means there was no server to talk to.
     */
    suspend fun stream(onChange: suspend (List<AdbDevice>) -> Unit) {
        // Long form first for `model:`, the only part of a device list a human recognises. An adb old
        // enough to reject it still answers the short form, where the serial has to do.
        if (consume(SERVICE_LONG, onChange)) return
        if (consume(SERVICE_SHORT, onChange)) return
        throw IOException("The adb server on port $port rejected host:track-devices.")
    }

    /** False if the server refused the service, which is the caller's cue to ask for a simpler one. */
    private suspend fun consume(service: String, onChange: suspend (List<AdbDevice>) -> Unit): Boolean =
        coroutineScope {
            val socket = Socket()
            // A blocking socket read does not answer to thread interruption, so cancelling the reader is
            // not enough to end it — the socket has to be closed underneath. Nor can a completion handler
            // on the reader do it: a coroutine stuck in that read never *completes*, it only sits in
            // cancelling, so the handler would fire after the deadlock it was meant to prevent. This
            // sibling is parked rather than blocked, which is what makes it reachable by cancellation.
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { socket.close() }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
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
                // Closing the socket is how cancellation gets out of that read, so the way out looks
                // exactly like adb dying. Being cancelled is not a failure to report as one.
                currentCoroutineContext().ensureActive()
                throw broken
            } finally {
                closer.cancel()
            }
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 2_000
        const val SERVICE_LONG = "host:track-devices-l"
        const val SERVICE_SHORT = "host:track-devices"

        /** Honoured by adb itself, and set by anyone running a second server beside Android Studio's. */
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

/** The server's verdict on the request. A rejection is followed by a framed reason we have no use for. */
private fun InputStream.accepted(): Boolean = when (val status = readAscii(OKAY.length)) {
    OKAY -> true
    FAIL -> false
    null -> throw IOException("The adb server closed the connection without answering.")
    else -> throw IOException("The adb server answered '$status'.")
}

/** Null once the server hangs up. An empty string is a real answer: nothing is attached. */
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
