package com.venbiasa.wailo.engine

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A handle to one captured body's bytes.
 *
 * [size] is what the store actually holds. That is not the exchange's declared `body_size`, which stays
 * on the protobuf alongside `body_truncated` — a device that truncated during capture still reports how
 * long the body really was, and both numbers have to survive.
 */
data class BodyRef(val id: String, val size: Long)

/**
 * A body being written. Nothing is readable until [commit], so a spool interrupted halfway leaves no
 * handle for anything to find.
 */
interface BodySink : Closeable {
    fun write(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset)

    /** Seal the body and return its handle, or null when nothing was written. */
    fun commit(): BodyRef?

    /** Discards an uncommitted body; a no-op once [commit] has run. */
    override fun close()
}

/**
 * Where captured body bytes live once they are off the heap.
 *
 * A retained exchange used to cost its payloads, which made the length of a capture a function of how
 * much memory the traffic happened to need. That was survivable while every producer was an SDK with a
 * per-body cap; it is not survivable for a proxy, which has no cap and will happily stream something
 * larger than RAM. So the engine keeps metadata resident, hands the bytes here on arrival, and reads
 * them back by range only when something actually looks at them.
 *
 * Files, encryption, and eviction belong to the implementation — the engine stays headless and knows
 * nothing about storage, the same seam [MapLocalBodyProvider] draws for Map Local fixtures. The daemon
 * supplies the disk-backed one; [InMemoryBodyStore] keeps the old behaviour for an engine with no
 * daemon behind it.
 */
interface BodyStore : Closeable {
    fun openSink(): BodySink

    /**
     * Read up to [length] bytes from [offset]. A short (or empty) result means the body ended first;
     * an unknown or already-released [ref] reads as empty rather than throwing, because a row can be
     * evicted while a viewer is still asking about it.
     */
    fun read(ref: BodyRef, offset: Long, length: Int): ByteArray

    /** Stream a whole body, for callers that can consume it incrementally. The caller closes it. */
    fun open(ref: BodyRef): InputStream

    /** Drop the bytes behind [refs], called when their exchanges fall off the retention window. */
    fun release(refs: Collection<BodyRef>)
}

/** Spool an already-materialized body. Empty bodies get no handle — there is nothing to fetch later. */
fun BodyStore.put(bytes: ByteString): BodyRef? {
    if (bytes.size == 0) return null
    return openSink().use { sink ->
        sink.write(bytes.toByteArray())
        sink.commit()
    }
}

/**
 * Read a body back with a ceiling on what is allowed into memory. The ceiling is the point of the whole
 * store, so every caller that wants "the body" as a value takes one.
 */
fun BodyStore.readAtMost(ref: BodyRef?, limit: Int): ByteString {
    if (ref == null || limit <= 0) return ByteString.EMPTY
    return read(ref, 0, minOf(limit.toLong(), ref.size).toInt()).toByteString()
}

/**
 * Keeps bodies on the heap, which is what the engine did before there was a store at all. Used by tests
 * and by any engine constructed without a daemon; the retention cap remains the only thing bounding it.
 */
class InMemoryBodyStore : BodyStore {
    private val bodies = ConcurrentHashMap<String, ByteArray>()

    override fun openSink(): BodySink = object : BodySink {
        private val buffer = okio.Buffer()
        private var committed = false

        override fun write(chunk: ByteArray, offset: Int, length: Int) {
            buffer.write(chunk, offset, length)
        }

        override fun commit(): BodyRef? {
            if (committed || buffer.size == 0L) return null
            committed = true
            val bytes = buffer.readByteArray()
            val id = UUID.randomUUID().toString()
            bodies[id] = bytes
            return BodyRef(id, bytes.size.toLong())
        }

        override fun close() {
            buffer.clear()
        }
    }

    override fun read(ref: BodyRef, offset: Long, length: Int): ByteArray {
        val bytes = bodies[ref.id] ?: return ByteArray(0)
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val from = offset.toInt()
        val to = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(from, to)
    }

    override fun open(ref: BodyRef): InputStream =
        ByteArrayInputStream(bodies[ref.id] ?: ByteArray(0))

    override fun release(refs: Collection<BodyRef>) {
        refs.forEach { bodies.remove(it.id) }
    }

    override fun close() {
        bodies.clear()
    }
}
