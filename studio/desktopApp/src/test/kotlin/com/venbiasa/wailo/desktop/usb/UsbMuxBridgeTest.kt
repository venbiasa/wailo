package com.venbiasa.wailo.desktop.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals

class UsbMuxBridgeTest {

    @Test
    fun forwardsBytesInBothDirections() = runBlocking {
        ServerSocketChannel.open().use { tunnelServer ->
            tunnelServer.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val tunnelAddress = tunnelServer.localAddress as InetSocketAddress
            val tunnel = SocketChannel.open(tunnelAddress)
            tunnelServer.accept().use { device ->
                UsbMuxBridge(tunnel).use { bridge ->
                    val forwarding = launch(Dispatchers.IO) { bridge.run() }
                    Socket(InetAddress.getLoopbackAddress(), bridge.port).use { local ->
                        val outbound = byteArrayOf(1, 2, 3, 4)
                        local.getOutputStream().write(outbound)
                        local.getOutputStream().flush()
                        assertContentEquals(outbound, withContext(Dispatchers.IO) { device.readBytes(outbound.size) })

                        val inbound = byteArrayOf(5, 6, 7)
                        withContext(Dispatchers.IO) { device.writeFully(ByteBuffer.wrap(inbound)) }
                        assertContentEquals(inbound, withContext(Dispatchers.IO) { local.getInputStream().readNBytes(inbound.size) })
                    }
                    bridge.close()
                    forwarding.join()
                }
            }
        }
    }
}

private fun SocketChannel.readBytes(size: Int): ByteArray {
    val buffer = ByteBuffer.allocate(size)
    while (buffer.hasRemaining()) check(read(buffer) >= 0)
    return buffer.array()
}

private fun SocketChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}
