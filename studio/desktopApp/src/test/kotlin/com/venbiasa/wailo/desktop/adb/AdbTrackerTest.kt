package com.venbiasa.wailo.desktop.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Run against a scripted stand-in for the adb server, because the shapes worth pinning are the ones a
 * real one only produces when someone physically unplugs a phone.
 */
class AdbTrackerTest {

    private val ready = "R5CT10ABCDE            device usb:338690048X product:a52qxx model:SM_A525F transport_id:2\n"

    @Test
    fun `the list is pushed, and asked for only once`() {
        val server = FakeAdbServer { _, output ->
            output.okay()
            output.frame(ready)
        }

        val seen = server.use { collect(it) }

        assertEquals("SM A525F", seen.single().single().label)
        // The whole point: one question, and the answers keep coming.
        assertEquals(listOf("host:track-devices-l"), server.services)
    }

    @Test
    fun `every change is an emission`() {
        val server = FakeAdbServer { _, output ->
            output.okay()
            output.frame(ready)
            // Unplugged. A zero-length frame is an answer, not a hang-up — reading it as one would
            // strand the panel showing a device that is no longer there.
            output.frame("")
            output.frame(ready)
        }

        val seen = server.use { collect(it) }

        assertEquals(listOf(1, 0, 1), seen.map { it.size })
    }

    @Test
    fun `an adb too old for the long form is asked again for the short one`() {
        val server = FakeAdbServer { service, output ->
            if (service.endsWith("-l")) {
                output.write("FAIL".toByteArray())
                output.frame("unknown host service")
            } else {
                output.okay()
                output.frame("R5CT10ABCDE\tdevice\n")
            }
        }

        val seen = server.use { collect(it) }

        assertEquals(listOf("host:track-devices-l", "host:track-devices"), server.services)
        // No `model:` in the short form, so the serial is the only name there is to show.
        assertEquals("R5CT10ABCDE", seen.single().single().label)
    }

    @Test
    fun `the server hanging up returns rather than throws, because it is how adb restarts`() {
        val server = FakeAdbServer { _, output ->
            output.okay()
            output.frame(ready)
        }

        // Reconnecting is the caller's job; getting here without an exception is what lets it be.
        server.use { collect(it) }
    }

    @Test
    fun `no server to talk to is a failure, so the caller can go back to asking`() {
        val port = ServerSocket(0).use { it.localPort }

        assertFailsWith<IOException> {
            runBlocking { AdbTracker(port).stream { } }
        }
    }

    @Test
    fun `a server that refuses every form is a failure too`() {
        val server = FakeAdbServer { _, output ->
            output.write("FAIL".toByteArray())
            output.frame("unknown host service")
        }

        server.use {
            assertFailsWith<IOException> { collect(it) }
        }
        assertEquals(2, server.services.size)
    }

    @Test
    fun `a length that is not a length is not read as devices`() {
        val server = FakeAdbServer { _, output ->
            output.okay()
            output.write("zzzz".toByteArray())
        }

        server.use {
            val failure = assertFailsWith<IOException> { collect(it) }
            assertTrue(failure.message.orEmpty().contains("zzzz"), failure.message.orEmpty())
        }
    }

    @Test
    fun `a frame that promises more than it sends is not read as a short list`() {
        val server = FakeAdbServer { _, output ->
            output.okay()
            // A list cut off mid-device parses into something plausible and wrong. Better to drop the
            // connection and reconnect than to tell the panel a phone was unplugged.
            output.write("0064".toByteArray())
            output.write(ready.toByteArray())
        }

        server.use {
            val failure = assertFailsWith<IOException> { collect(it) }
            assertTrue(failure.message.orEmpty().contains("short"), failure.message.orEmpty())
        }
    }

    /**
     * The one check a fake cannot make: that a real adb still speaks this.
     *
     * `host:track-devices` is a service Google documents but does not promise to keep, so the risk this
     * change takes on is a future platform-tools quietly dropping it — which no amount of scripted
     * server proves anything about. Skipped where there is no adb server, which is every CI machine, so
     * it earns its keep on the developer machines that would notice first.
     *
     * Cancelling at the end is load-bearing too: a blocking socket read ignores interruption, so if
     * [AdbTracker] ever stops closing the socket underneath it, this hangs rather than passes.
     */
    @Test
    fun `a real adb server, when there is one, still pushes a list`() = runBlocking {
        val port = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037
        if (!listening(port)) return@runBlocking

        val first = CompletableDeferred<List<AdbDevice>>()
        val job = launch(Dispatchers.IO) { AdbTracker(port).stream { first.complete(it) } }
        val devices = withTimeoutOrNull(10_000) { first.await() }
        job.cancelAndJoin()

        // The list is allowed to be empty — nothing need be plugged in for the server to answer.
        assertNotNull(devices, "The adb server on $port accepted the connection but pushed nothing.")
    }

    private fun listening(port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) } }.isSuccess

    private fun collect(server: FakeAdbServer): List<List<AdbDevice>> {
        val seen = mutableListOf<List<AdbDevice>>()
        runBlocking { AdbTracker(server.port).stream { seen += it } }
        return seen
    }
}

private fun OutputStream.okay() = write("OKAY".toByteArray())

private fun OutputStream.frame(payload: String) {
    write("%04x%s".format(payload.length, payload).toByteArray())
    flush()
}

/**
 * Speaks enough of the adb server's side to answer one request per connection, then hangs up — which is
 * also how a real one behaves when it is killed mid-track.
 */
private class FakeAdbServer(private val script: (String, OutputStream) -> Unit) : Closeable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    val services = CopyOnWriteArrayList<String>()

    private val thread = Thread {
        runCatching {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    val service = socket.getInputStream().readRequest() ?: return@use
                    services += service
                    // The tracker drops the connection the moment it is refused, so the write racing
                    // with its close is expected rather than a fault in the script.
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
    val header = ByteArray(4)
    if (readNBytes(header, 0, 4) < 4) return null
    val length = String(header).toInt(16)
    val payload = ByteArray(length)
    if (readNBytes(payload, 0, length) < length) return null
    return String(payload)
}
