package com.venbiasa.wailo.desktop.usb

import org.w3c.dom.Element
import java.io.EOFException
import java.io.IOException
import java.io.StringReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal data class UsbmuxDevice(
    val handle: Int,
    val udid: String,
    val productId: Int,
)

internal sealed interface UsbmuxEvent {
    data class Attached(val device: UsbmuxDevice) : UsbmuxEvent
    data class Detached(val handle: Int) : UsbmuxEvent
}

internal class UsbmuxException(
    message: String,
    val resultCode: Int? = null,
) : IOException(message)

/**
 * Minimal plist-v1 client for Apple's built-in usbmuxd. A Listen socket receives device events; every
 * successful Connect socket becomes a raw byte stream to one localhost port on that device.
 */
internal class UsbmuxdClient(
    private val openChannel: () -> SocketChannel = {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(Path.of(SOCKET_PATH)))
        }
    },
) {
    private val nextTag = AtomicInteger()

    fun listen(onEvent: (UsbmuxEvent) -> Unit) {
        openChannel().use { channel ->
            val tag = nextTag.incrementAndGet()
            writePlist(channel, tag, command("Listen"))
            requireSuccess(readPacket(channel))
            while (true) {
                when (val message = parseMessage(readPacket(channel).payload)) {
                    is Incoming.Attached -> {
                        if (message.connectionType == "USB") {
                            onEvent(
                                UsbmuxEvent.Attached(
                                    UsbmuxDevice(message.deviceId, normalizeUdid(message.udid), message.productId),
                                ),
                            )
                        }
                    }
                    is Incoming.Detached -> onEvent(UsbmuxEvent.Detached(message.deviceId))
                    else -> Unit
                }
            }
        }
    }

    /**
     * The daemon's current view of attached devices. Polled alongside [listen] because an Attached event
     * can be missed — after a sleep/wake cycle the Listen socket stays open but silent — and without this
     * the only recovery is unplugging the cable.
     */
    fun listDevices(): List<UsbmuxDevice> = openChannel().use { channel ->
        val tag = nextTag.incrementAndGet()
        writePlist(channel, tag, command("ListDevices"))
        parseDeviceList(readPacket(channel).payload)
    }

    /**
     * Opens a tunnel to [port] on the device. On success the returned channel no longer carries usbmux
     * packets; it is the application's raw TCP stream and the caller owns it.
     */
    fun connect(deviceHandle: Int, port: Int): SocketChannel {
        require(port in 1..65535)
        val channel = openChannel()
        try {
            val tag = nextTag.incrementAndGet()
            writePlist(
                channel,
                tag,
                command(
                    "Connect",
                    "DeviceID" to integer(deviceHandle),
                    "PortNumber" to integer(swapPortBytes(port)),
                ),
            )
            requireSuccess(readPacket(channel))
            return channel
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    private fun requireSuccess(packet: Packet) {
        val result = parseMessage(packet.payload) as? Incoming.Result
            ?: throw UsbmuxException("usbmuxd returned an unexpected response")
        if (result.number != RESULT_OK) {
            throw UsbmuxException("usbmuxd request failed with result ${result.number}", result.number)
        }
    }

    private fun writePlist(channel: SocketChannel, tag: Int, xml: String) {
        val payload = xml.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(HEADER_SIZE + payload.size)
            .putInt(PLIST_VERSION)
            .putInt(MESSAGE_PLIST)
            .putInt(tag)
        header.flip()
        channel.writeFully(header)
        channel.writeFully(ByteBuffer.wrap(payload))
    }

    private fun readPacket(channel: SocketChannel): Packet {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        channel.readFully(header)
        header.flip()
        val length = header.int
        val version = header.int
        val message = header.int
        val tag = header.int
        if (length < HEADER_SIZE || length > MAX_PACKET_SIZE) {
            throw UsbmuxException("Invalid usbmuxd packet length $length")
        }
        if (version != PLIST_VERSION || message != MESSAGE_PLIST) {
            throw UsbmuxException("Unsupported usbmuxd packet version=$version message=$message")
        }
        val payload = ByteBuffer.allocate(length - HEADER_SIZE)
        channel.readFully(payload)
        return Packet(tag, payload.array())
    }

    private data class Packet(val tag: Int, val payload: ByteArray)

    private companion object {
        const val SOCKET_PATH = "/var/run/usbmuxd"
        const val HEADER_SIZE = 16
        const val PLIST_VERSION = 1
        const val MESSAGE_PLIST = 8
        const val RESULT_OK = 0
        const val MAX_PACKET_SIZE = 4 * 1024 * 1024
    }
}

private sealed interface Incoming {
    data class Result(val number: Int) : Incoming
    data class Attached(
        val deviceId: Int,
        val udid: String,
        val productId: Int,
        val connectionType: String,
    ) : Incoming
    data class Detached(val deviceId: Int) : Incoming
    data object Unknown : Incoming
}

private fun parseRoot(bytes: ByteArray): Element? =
    newDocumentBuilderFactory().newDocumentBuilder()
        .parse(InputSource(StringReader(bytes.toString(StandardCharsets.UTF_8))))
        .documentElement
        .firstElement()

private fun parseMessage(bytes: ByteArray): Incoming {
    val root = parseRoot(bytes) ?: return Incoming.Unknown
    val values = root.asDict()
    return when (values["MessageType"]?.textContent) {
        "Result" -> Incoming.Result(values["Number"].intValue())
        "Attached" -> values.toAttached()
        "Detached" -> Incoming.Detached(values["DeviceID"].intValue())
        else -> Incoming.Unknown
    }
}

private fun parseDeviceList(bytes: ByteArray): List<UsbmuxDevice> {
    val root = parseRoot(bytes) ?: return emptyList()
    return root.asDict()["DeviceList"]?.childElements().orEmpty()
        .map { entry -> entry.asDict().toAttached() }
        .filter { it.connectionType == "USB" }
        .map { UsbmuxDevice(it.deviceId, normalizeUdid(it.udid), it.productId) }
}

private fun Map<String, Element>.toAttached(): Incoming.Attached {
    val properties = this["Properties"]?.asDict().orEmpty()
    return Incoming.Attached(
        deviceId = this["DeviceID"].intValue(properties["DeviceID"].intValue()),
        udid = properties["SerialNumber"]?.textContent.orEmpty(),
        productId = properties["ProductID"].intValue(),
        connectionType = properties["ConnectionType"]?.textContent.orEmpty(),
    )
}

private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

private fun Element.asDict(): Map<String, Element> {
    val children = childElements()
    val result = mutableMapOf<String, Element>()
    var index = 0
    while (index + 1 < children.size) {
        val key = children[index]
        if (key.tagName == "key") result[key.textContent] = children[index + 1]
        index += 2
    }
    return result
}

private fun Element.childElements(): List<Element> {
    val result = mutableListOf<Element>()
    var child = firstChild
    while (child != null) {
        if (child is Element) result += child
        child = child.nextSibling
    }
    return result
}

private fun Element.firstElement(): Element? {
    var child = firstChild
    while (child != null) {
        if (child is Element) return child
        child = child.nextSibling
    }
    return null
}

private fun Element?.intValue(default: Int = 0): Int = this?.textContent?.toLongOrNull()?.toInt() ?: default

private fun command(type: String, vararg values: Pair<String, String>): String {
    val fields = buildString {
        append(key("MessageType", string(type)))
        append(key("ClientVersionString", string("Wailo Studio 1.0")))
        append(key("ProgName", string("Wailo")))
        append(key("kLibUSBMuxVersion", integer(3)))
        values.forEach { (name, value) -> append(key(name, value)) }
    }
    return """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" """ +
        """"http://www.apple.com/DTDs/PropertyList-1.0.dtd">""" +
        """<plist version="1.0"><dict>$fields</dict></plist>"""
}

private fun key(name: String, value: String): String = "<key>$name</key>$value"
private fun string(value: String): String = "<string>$value</string>"
private fun integer(value: Int): String = "<integer>$value</integer>"

internal fun swapPortBytes(port: Int): Int =
    ((port and 0xff) shl 8) or ((port ushr 8) and 0xff)

private fun normalizeUdid(value: String): String =
    if (value.length == 24 && '-' !in value) value.substring(0, 8) + "-" + value.substring(8) else value

private fun SocketChannel.readFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (read(buffer) < 0) throw EOFException("usbmuxd closed the connection")
    }
}

private fun SocketChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}
