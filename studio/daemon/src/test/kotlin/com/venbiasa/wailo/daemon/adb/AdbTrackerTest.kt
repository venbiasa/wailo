package com.venbiasa.wailo.daemon.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import java.io.IOException

class AdbTrackerTest {
    @Test
    fun receivesEveryPushedListOverOneServiceRequest() {
        FakeAdbServer { _, output ->
            output.write("OKAY".toByteArray())
            output.frame("R5CT10ABCDE device model:SM_A525F\n")
            output.frame("")
        }.use { server ->
            val seen = mutableListOf<List<AdbDevice>>()
            runBlocking { AdbTracker(server.port).stream { seen += it } }

            assertEquals(listOf(1, 0), seen.map { it.size })
            assertEquals(listOf("host:track-devices-l"), server.services)
        }
    }

    @Test
    fun fallsBackToShortServiceWhenLongFormIsRejected() {
        FakeAdbServer { service, output ->
            if (service.endsWith("-l")) {
                output.write("FAIL".toByteArray())
                output.frame("unsupported")
            } else {
                output.write("OKAY".toByteArray())
                output.frame("R5CT10ABCDE device\n")
            }
        }.use { server ->
            val seen = mutableListOf<List<AdbDevice>>()
            runBlocking { AdbTracker(server.port).stream { seen += it } }

            assertEquals(listOf("host:track-devices-l", "host:track-devices"), server.services)
            assertEquals("R5CT10ABCDE", seen.single().single().label)
        }
    }

    @Test
    fun rejectsMalformedFrameLengths() {
        FakeAdbServer { _, output ->
            output.write("OKAYzzzz".toByteArray())
            output.flush()
        }.use { server ->
            assertFailsWith<IOException> {
                runBlocking { AdbTracker(server.port).stream { } }
            }
        }
    }
}

private fun OutputStream.frame(payload: String) {
    write("%04x%s".format(payload.length, payload).toByteArray())
    flush()
}

private class FakeAdbServer(
    private val script: (String, OutputStream) -> Unit,
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val services = CopyOnWriteArrayList<String>()
    private val thread = Thread {
        runCatching {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    val service = socket.getInputStream().readRequest() ?: return@use
                    services += service
                    runCatching { script(service, socket.getOutputStream()) }
                }
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        server.close()
        thread.join(1_000)
    }
}

private fun InputStream.readRequest(): String? {
    val header = readNBytes(4)
    if (header.size != 4) return null
    val length = String(header).toInt(16)
    val payload = readNBytes(length)
    return payload.takeIf { it.size == length }?.let(::String)
}
