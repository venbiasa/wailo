package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.Header
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A parsed start line plus headers, and the exact bytes they arrived as.
 *
 * [raw] exists so a response head can be forwarded byte for byte while still being understood here: a
 * proxy that re-serialized every message would normalize away casing, ordering, and duplicate headers
 * that the client may well be depending on.
 */
internal class HttpHead(
    val first: String,
    val second: String,
    val third: String,
    val headers: List<Header>,
    val raw: ByteArray,
) {
    fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value_

    fun contentLength(): Long? = header("Content-Length")?.trim()?.toLongOrNull()

    fun isChunked(): Boolean = header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true

    /** Whether the peer asked to end the connection after this message. */
    fun wantsClose(): Boolean = header("Connection")?.contains("close", ignoreCase = true) == true
}

/**
 * Headers that describe *this* hop rather than the message, so forwarding them would be a lie. Framing
 * headers are kept deliberately: request and response bodies are relayed with their original framing.
 */
private val HopByHopHeaders = setOf(
    "connection",
    "proxy-connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "upgrade",
)

internal fun List<Header>.withoutHopByHop(): List<Header> =
    filterNot { it.name.lowercase() in HopByHopHeaders }

/** Guards against a peer that opens a connection and streams header bytes forever. */
private const val MAX_HEAD_BYTES = 64 * 1024

private const val MAX_LINE_BYTES = 8 * 1024

internal const val RELAY_BUFFER_BYTES = 32 * 1024

/**
 * Read one message head. Returns null at a clean end of stream, which on a kept-alive connection just
 * means the peer is done rather than that anything went wrong.
 */
internal fun readHead(input: InputStream): HttpHead? {
    val raw = ByteArrayOutputStream(1024)
    val start = readLine(input, raw) ?: return null
    // A stray empty line before the start line is legal slop after a previous message (RFC 9112).
    val startLine = start.ifBlank { readLine(input, raw) ?: return null }
    if (startLine.isBlank()) return null
    val parts = startLine.split(' ', limit = 3)
    if (parts.size < 2) throw IOException("Malformed HTTP start line")
    val headers = mutableListOf<Header>()
    while (true) {
        if (raw.size() > MAX_HEAD_BYTES) throw IOException("HTTP head too large")
        val line = readLine(input, raw) ?: throw EOFException("Truncated HTTP head")
        if (line.isEmpty()) break
        val separator = line.indexOf(':')
        if (separator <= 0) continue
        headers += Header(
            name = line.substring(0, separator).trim(),
            value_ = line.substring(separator + 1).trim(),
        )
    }
    return HttpHead(
        first = parts[0],
        second = parts[1],
        third = parts.getOrElse(2) { "HTTP/1.1" },
        headers = headers,
        raw = raw.toByteArray(),
    )
}

private fun readLine(input: InputStream, raw: ByteArrayOutputStream): String? {
    val line = ByteArrayOutputStream(128)
    while (true) {
        val next = input.read()
        if (next < 0) return if (line.size() == 0 && raw.size() == 0) null else throw EOFException("Truncated HTTP line")
        raw.write(next)
        if (next == '\n'.code) break
        if (line.size() > MAX_LINE_BYTES) throw IOException("HTTP line too long")
        line.write(next)
    }
    // ISO-8859-1 round-trips every byte, so a header carrying non-UTF-8 bytes survives being read here.
    return line.toByteArray().toString(Charsets.ISO_8859_1).removeSuffix("\r")
}

/**
 * Mirrors every byte read to [mirror], so a body can be parsed and forwarded in one pass. Framing is
 * echoed along with content — chunk sizes and trailers included — which is what keeps the relay
 * byte-exact even where this code understands the message well enough to have rewritten it.
 */
internal class EchoInputStream(
    private val source: InputStream,
    private val mirror: OutputStream?,
) : InputStream() {
    override fun read(): Int {
        val value = source.read()
        if (value >= 0) mirror?.let {
            it.write(value)
            it.flush()
        }
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val read = source.read(destination, offset, length)
        if (read > 0) mirror?.let {
            it.write(destination, offset, read)
            it.flush()
        }
        return read
    }
}

/** The entity body of a message framed by `Content-Length`. */
internal class FixedLengthInputStream(
    private val source: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = source.read()
        if (value >= 0) remaining -= 1
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = source.read(destination, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }
}

/**
 * The entity body of a `Transfer-Encoding: chunked` message. Yields the decoded content while the
 * underlying [EchoInputStream] passes the original framing to the far side.
 */
internal class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var remaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (remaining == 0L && !startChunk()) return -1
        if (length == 0) return 0
        val read = source.read(destination, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Truncated chunk")
        remaining -= read
        if (remaining == 0L) consumeCrLf()
        return read
    }

    /** Returns false at the terminating zero-length chunk, having consumed its trailers. */
    private fun startChunk(): Boolean {
        val header = readRawLine()
        // A chunk header may carry extensions after a `;`, which say nothing about the size.
        val size = header.substringBefore(';').trim().toLongOrNull(16)
            ?: throw IOException("Malformed chunk size: $header")
        if (size == 0L) {
            while (readRawLine().isNotEmpty()) Unit
            finished = true
            return false
        }
        remaining = size
        return true
    }

    private fun consumeCrLf() {
        readRawLine()
    }

    private fun readRawLine(): String {
        val line = ByteArrayOutputStream(32)
        while (true) {
            val next = source.read()
            if (next < 0) throw EOFException("Truncated chunk framing")
            if (next == '\n'.code) break
            if (line.size() > MAX_LINE_BYTES) throw IOException("Chunk framing line too long")
            line.write(next)
        }
        return line.toByteArray().toString(Charsets.ISO_8859_1).removeSuffix("\r").trim()
    }
}

/**
 * Present the entity as the bytes a human would want to read.
 *
 * The client still receives exactly what the origin sent — decoding happens on the inspection side of
 * the tee — so this cannot corrupt the transfer. An encoding we do not know is left alone and simply
 * spools compressed, which is better than refusing to record it.
 */
internal fun decodedForCapture(entity: InputStream, contentEncoding: String?): InputStream =
    when (contentEncoding?.trim()?.lowercase()) {
        "gzip", "x-gzip" -> GZIPInputStream(entity)
        "deflate" -> InflaterInputStream(entity, Inflater(true))
        else -> entity
    }
