package com.venbiasa.wailo.daemon.usb

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsbmuxdClientTest {
    @Test
    fun connectEncodesDevicePortInNetworkByteOrder() {
        withFakeDaemon { client, server ->
            var request = ""
            val daemon = thread {
                server.accept().use { socket ->
                    val packet = socket.readPacket()
                    request = packet.payload.toString(StandardCharsets.UTF_8)
                    socket.writePacket(packet.tag, result(0))
                }
            }
            client.connect(deviceHandle = 7, port = 22).close()
            daemon.join()

            assertTrue(request.contains("<key>DeviceID</key><integer>7</integer>"))
            assertTrue(request.contains("<key>PortNumber</key><integer>5632</integer>"))
        }
    }

    @Test
    fun listenerFiltersNetworkDevicesAndNormalizesUsbUdid() {
        withFakeDaemon { client, server ->
            val daemon = thread {
                server.accept().use { socket ->
                    val request = socket.readPacket()
                    socket.writePacket(request.tag, result(0))
                    socket.writePacket(0, attached(12, "00008101001578312603001E", "USB"))
                    socket.writePacket(0, attached(13, "network-device", "Network"))
                    socket.writePacket(0, detached(12))
                }
            }
            val events = mutableListOf<UsbmuxEvent>()
            runCatching { client.listen(events::add) }
            daemon.join()

            assertEquals("00008101-001578312603001E", assertIs<UsbmuxEvent.Attached>(events[0]).device.udid)
            assertEquals(12, assertIs<UsbmuxEvent.Detached>(events[1]).handle)
            assertEquals(2, events.size)
        }
    }

    @Test
    fun listDevicesUsesAuthoritativeUsbOnlySnapshot() {
        withFakeDaemon { client, server ->
            val daemon = thread {
                server.accept().use { socket ->
                    val request = socket.readPacket()
                    socket.writePacket(
                        request.tag,
                        deviceList(
                            attachedEntry(12, "00008101001578312603001E", "USB"),
                            attachedEntry(13, "network-device", "Network"),
                        ),
                    )
                }
            }
            val devices = client.listDevices()
            daemon.join()

            assertEquals(listOf("00008101-001578312603001E"), devices.map { it.udid })
        }
    }

    @Test
    fun portSwapMatchesUsbmuxProtocol() {
        assertEquals(5632, swapPortBytes(22))
        assertEquals(32498, swapPortBytes(62078))
    }

    private fun withFakeDaemon(block: (UsbmuxdClient, ServerSocketChannel) -> Unit) {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            block(UsbmuxdClient { SocketChannel.open(server.localAddress) }, server)
        }
    }
}

private data class TestPacket(val tag: Int, val payload: ByteArray)

private fun SocketChannel.readPacket(): TestPacket {
    val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    readFully(header)
    header.flip()
    val length = header.int
    header.int
    header.int
    val tag = header.int
    val payload = ByteBuffer.allocate(length - 16)
    readFully(payload)
    return TestPacket(tag, payload.array())
}

private fun SocketChannel.writePacket(tag: Int, body: String) {
    val payload = body.toByteArray(StandardCharsets.UTF_8)
    val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(16 + payload.size)
        .putInt(1)
        .putInt(8)
        .putInt(tag)
    header.flip()
    while (header.hasRemaining()) write(header)
    val content = ByteBuffer.wrap(payload)
    while (content.hasRemaining()) write(content)
}

private fun SocketChannel.readFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) check(read(buffer) >= 0)
}

private fun result(code: Int): String =
    plist("<key>MessageType</key><string>Result</string><key>Number</key><integer>$code</integer>")

private fun attached(deviceId: Int, udid: String, connectionType: String): String =
    plist(attachedEntry(deviceId, udid, connectionType))

private fun attachedEntry(deviceId: Int, udid: String, connectionType: String): String =
    "<key>MessageType</key><string>Attached</string>" +
        "<key>DeviceID</key><integer>$deviceId</integer>" +
        "<key>Properties</key><dict>" +
        "<key>DeviceID</key><integer>$deviceId</integer>" +
        "<key>SerialNumber</key><string>$udid</string>" +
        "<key>ProductID</key><integer>4776</integer>" +
        "<key>ConnectionType</key><string>$connectionType</string>" +
        "</dict>"

private fun deviceList(vararg entries: String): String =
    plist("<key>DeviceList</key><array>" + entries.joinToString("") { "<dict>$it</dict>" } + "</array>")

private fun detached(deviceId: Int): String =
    plist("<key>MessageType</key><string>Detached</string><key>DeviceID</key><integer>$deviceId</integer>")

private fun plist(content: String): String =
    """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>$content</dict></plist>"""
