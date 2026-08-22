package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.BodySink
import com.venbiasa.wailo.engine.BodyStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The daemon's on-disk body spool: captured payloads encrypted under a key that exists only in this
 * process's memory, in a directory that exists only for this daemon's lifetime.
 *
 * Why encrypted at all, for files we delete on the way out: captured traffic is the most sensitive thing
 * Wailo touches — session cookies, bearer tokens, whole authenticated responses — and moving it from a
 * process's heap to a file under `$HOME` widens who can read it from "this process" to "anything running
 * as this user, plus every backup and disk-recovery tool that ever sees the volume". A key that is never
 * written down keeps the blast radius where it was: a daemon that dies takes the only copy of the key
 * with it, so what it leaves behind is unreadable even to the next daemon.
 *
 * Bodies are chunked so a read can be a *range* read. Each 64 KiB of plaintext is sealed on its own with
 * a fresh nonce, and because every sealed chunk but the last is exactly the same length, chunk *i* sits
 * at a computable offset — a viewer scrolled to the middle of a 4 GiB response decrypts one chunk, not
 * four gigabytes. The chunk index and body id are authenticated as associated data, so chunks cannot be
 * reordered or grafted between bodies even by something that can write to the spool.
 */
class SpoolBodyStore private constructor(
    private val directory: Path,
    private val key: SecretKey,
    private val reserveBytes: Long,
) : BodyStore {
    private val random = SecureRandom()
    private val writtenSinceCheck = AtomicLong()

    /**
     * Called when the spool volume drops below [reserveBytes]. Set after construction rather than passed
     * in, because the only useful reaction is to retire exchanges — and the engine that owns them is
     * built around this store, so it cannot also be an argument to it.
     */
    @Volatile
    var onPressure: () -> Unit = {}

    override fun openSink(): BodySink = SpoolSink()

    override fun read(ref: BodyRef, offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0 || offset >= ref.size) return EMPTY
        val end = minOf(ref.size, offset + length)
        val first = (offset / CHUNK).toInt()
        val last = ((end - 1) / CHUNK).toInt()
        val collected = ByteArrayOutputStream((end - offset).toInt())
        return runCatching {
            FileChannel.open(fileFor(ref.id), StandardOpenOption.READ).use { channel ->
                for (index in first..last) {
                    val plain = decrypt(channel, ref, index) ?: return EMPTY
                    val chunkStart = index.toLong() * CHUNK
                    val from = maxOf(0L, offset - chunkStart).toInt()
                    val to = minOf(plain.size.toLong(), end - chunkStart).toInt()
                    if (to > from) collected.write(plain, from, to - from)
                }
            }
            collected.toByteArray()
        }.getOrDefault(EMPTY)
    }

    override fun open(ref: BodyRef): InputStream = ChunkStream(ref)

    override fun release(refs: Collection<BodyRef>) {
        refs.forEach { runCatching { Files.deleteIfExists(fileFor(it.id)) } }
    }

    /** Drop the whole session: the key goes with the process, and the bytes should not outlive it. */
    override fun close() {
        deleteRecursively(directory)
    }

    private fun fileFor(id: String): Path = directory.resolve("$id$SUFFIX")

    private fun decrypt(channel: FileChannel, ref: BodyRef, index: Int): ByteArray? {
        val plainLength = minOf(CHUNK.toLong(), ref.size - index.toLong() * CHUNK).toInt()
        if (plainLength <= 0) return null
        val frame = ByteBuffer.allocate(NONCE + plainLength + TAG)
        channel.position(index.toLong() * STRIDE)
        while (frame.hasRemaining()) {
            if (channel.read(frame) < 0) return null
        }
        val raw = frame.array()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, raw, 0, NONCE))
        cipher.updateAAD(associatedData(ref.id, index))
        return cipher.doFinal(raw, NONCE, plainLength + TAG)
    }

    // Sampled rather than checked per body: this is a stat() against the volume, and a capture that is
    // filling a disk is by definition writing a lot of small things quickly.
    private fun noteWrite(bytes: Long) {
        if (writtenSinceCheck.addAndGet(bytes) < PRESSURE_CHECK_BYTES) return
        writtenSinceCheck.set(0)
        val usable = runCatching { Files.getFileStore(directory).usableSpace }.getOrNull() ?: return
        if (usable < reserveBytes) onPressure()
    }

    private inner class SpoolSink : BodySink {
        private val id = UUID.randomUUID().toString()
        private val buffer = ByteArray(CHUNK)
        private var channel: FileChannel? = null
        private var filled = 0
        private var chunkIndex = 0
        private var total = 0L
        private var committed = false

        override fun write(chunk: ByteArray, offset: Int, length: Int) {
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val take = minOf(remaining, CHUNK - filled)
                System.arraycopy(chunk, position, buffer, filled, take)
                filled += take
                position += take
                remaining -= take
                if (filled == CHUNK) seal()
            }
        }

        override fun commit(): BodyRef? {
            if (committed) return null
            seal()
            committed = true
            val open = channel ?: return null
            channel = null
            // No force(): these bytes are session state that is deleted on the way out, so paying for
            // durability would buy nothing but latency on every captured response.
            runCatching { open.close() }
            if (total == 0L) return null
            noteWrite(total)
            return BodyRef(id, total)
        }

        override fun close() {
            val open = channel
            channel = null
            runCatching { open?.close() }
            if (!committed) runCatching { Files.deleteIfExists(fileFor(id)) }
        }

        private fun seal() {
            if (filled == 0) return
            val out = channel ?: openFile().also { channel = it }
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce))
            cipher.updateAAD(associatedData(id, chunkIndex))
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.position(chunkIndex.toLong() * STRIDE)
            writeFully(out, ByteBuffer.wrap(nonce))
            writeFully(out, ByteBuffer.wrap(sealed))
            total += filled
            chunkIndex += 1
            filled = 0
        }

        private fun openFile(): FileChannel {
            val path = fileFor(id)
            val opened = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            ownerOnly(path, directory = false)
            return opened
        }
    }

    /** Walks the chunks so an export can stream a body the machine could not hold. */
    private inner class ChunkStream(private val ref: BodyRef) : InputStream() {
        private var position = 0L
        private var buffer = EMPTY
        private var consumed = 0

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (consumed == buffer.size) {
                if (position >= ref.size) return -1
                buffer = this@SpoolBodyStore.read(ref, position, CHUNK)
                consumed = 0
                if (buffer.isEmpty()) return -1
                position += buffer.size
            }
            val take = minOf(length, buffer.size - consumed)
            System.arraycopy(buffer, consumed, destination, offset, take)
            consumed += take
            return take
        }

        override fun available(): Int = (buffer.size - consumed).coerceAtLeast(0)
    }

    companion object {
        /**
         * Plaintext bytes per sealed chunk. Big enough that the per-chunk nonce and tag are noise, small
         * enough that seeking to one byte never decrypts more than this.
         */
        private const val CHUNK = 64 * 1024

        private const val NONCE = 12
        private const val TAG = 16

        /**
         * On-disk bytes per chunk. Fixed, which is the whole trick: chunk *i* is at `i * STRIDE` with no
         * index to load or maintain, and the only short chunk is the last one — whose length falls out of
         * the total the handle already carries.
         */
        private const val STRIDE = NONCE + CHUNK + TAG

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SUFFIX = ".body"

        private val EMPTY = ByteArray(0)

        /** How much can be spooled between two checks of the volume's free space. */
        private const val PRESSURE_CHECK_BYTES = 32L * 1024 * 1024

        /** Leave this much of the volume alone; below it, exchanges start being retired. */
        const val DEFAULT_RESERVE_BYTES: Long = 2L * 1024 * 1024 * 1024

        /**
         * Open a fresh session spool under [root], first clearing whatever a previous daemon left there.
         * Safe because the caller holds the single-instance lock: no other daemon owns a sibling session,
         * and anything already present is encrypted under a key that died with the process that made it.
         */
        fun open(
            root: Path = wailoStateDir().resolve("bodies"),
            reserveBytes: Long = DEFAULT_RESERVE_BYTES,
        ): SpoolBodyStore {
            Files.createDirectories(root)
            ownerOnly(root, directory = true)
            runCatching {
                Files.newDirectoryStream(root).use { stale -> stale.forEach(::deleteRecursively) }
            }
            val session = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
            ownerOnly(session, directory = true)
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return SpoolBodyStore(session, key, reserveBytes)
        }

        private fun associatedData(id: String, index: Int): ByteArray = "$id/$index".toByteArray()

        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun deleteRecursively(path: Path) {
            runCatching {
                if (Files.isDirectory(path)) {
                    Files.newDirectoryStream(path).use { children -> children.forEach(::deleteRecursively) }
                }
                Files.deleteIfExists(path)
            }
        }

        private fun ownerOnly(target: Path, directory: Boolean) {
            val permissions = if (directory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            runCatching { Files.setPosixFilePermissions(target, permissions) }
        }
    }
}
