package com.venbiasa.wailo.daemon

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/** A loopback origin the proxy tests relay to, plain or TLS depending on the socket handed in. */
internal class TestOrigin(private val socket: ServerSocket) : Closeable {
    val port: Int get() = socket.localPort

    override fun close() = socket.close()
}

/** Bound and released, so the port is almost certainly nobody's. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

internal fun OutputStream.respond(body: String) {
    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
    flush()
}

internal fun InputStream.readLineOrNull(): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
        if (next == '\n'.code) break
        line.write(next)
    }
    return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
}

/** Read a request head to its blank line, leaving the stream on the body. */
internal fun InputStream.readRequestHead(): List<String> = buildList {
    while (true) {
        val line = readLineOrNull() ?: break
        if (line.isBlank()) break
        add(line)
    }
}
