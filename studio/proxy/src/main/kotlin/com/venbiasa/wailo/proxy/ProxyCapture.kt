package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.HttpExchange
import java.io.Closeable

/**
 * A body the sink has taken, and how many bytes of it there are.
 *
 * Mirrors `engine.BodyRef` by value rather than importing it: `proxy` deliberately depends on nothing
 * but `protocol` and the JDK (ADR-0070), so the relay cannot reach capture state. The daemon maps
 * between the two.
 */
data class ProxyBodyRef(val id: String, val size: Long)

/** A body being written as it is relayed. Nothing is readable until [commit]. */
interface ProxyBodySink : Closeable {
    fun write(chunk: ByteArray, offset: Int, length: Int)

    /** Seal the body and return its handle, or null when nothing was written. */
    fun commit(): ProxyBodyRef?

    /** Discards an uncommitted body; a no-op once [commit] has run. */
    override fun close()
}

/**
 * Where relayed traffic goes. The proxy never holds a whole body: it opens a sink, streams into it
 * while the bytes are on their way to the client or the origin, and hands over the handle.
 *
 * [client] is the peer address the request came from, which is all a proxy knows about who is calling —
 * there is no `Hello` and no pairing here, so it is recorded as an identity rather than as a device.
 */
interface ProxyCaptureSink {
    fun openBody(): ProxyBodySink

    fun record(
        exchange: HttpExchange,
        client: String,
        requestBody: ProxyBodyRef?,
        responseBody: ProxyBodyRef?,
    )

    /** Whether traffic should be recorded at all; a paused capture still relays, it just keeps nothing. */
    fun isRecording(): Boolean = true
}
