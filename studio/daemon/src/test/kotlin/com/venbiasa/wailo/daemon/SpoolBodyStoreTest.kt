package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.put
import com.venbiasa.wailo.engine.readAtMost
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class SpoolBodyStoreTest {

    private val root: Path = createTempDirectory("wailo-spool-test")

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aSpooledBodyReadsBackByteForByte() {
        val store = SpoolBodyStore.open(root)
        val body = payload(200_000)

        val ref = assertNotNull(store.put(body.toByteString()))

        assertEquals(body.size.toLong(), ref.size)
        assertContentEquals(body, store.read(ref, 0, body.size))
        store.close()
    }

    /**
     * The point of the chunk layout: a slice from the middle costs the chunks it overlaps, and the
     * boundaries between them are exactly where an off-by-one would hide.
     */
    @Test
    fun rangesReadCorrectlyAcrossChunkBoundaries() {
        val store = SpoolBodyStore.open(root)
        val body = payload(200_000)
        val ref = assertNotNull(store.put(body.toByteString()))

        listOf(
            0L to 10,
            65_530L to 12,
            65_536L to 1,
            131_060L to 200,
            199_999L to 1,
        ).forEach { (offset, length) ->
            assertContentEquals(
                body.copyOfRange(offset.toInt(), offset.toInt() + length),
                store.read(ref, offset, length),
                "range $offset..${offset + length}",
            )
        }
        store.close()
    }

    @Test
    fun aReadPastTheEndStopsAtTheEndRatherThanFailing() {
        val store = SpoolBodyStore.open(root)
        val body = payload(100)
        val ref = assertNotNull(store.put(body.toByteString()))

        assertContentEquals(body.copyOfRange(90, 100), store.read(ref, 90, 1_000))
        assertContentEquals(ByteArray(0), store.read(ref, 100, 10))
        store.close()
    }

    @Test
    fun anEmptyBodyGetsNoHandleBecauseThereIsNothingToFetchLater() {
        val store = SpoolBodyStore.open(root)

        assertNull(store.put(okio.ByteString.EMPTY))

        store.close()
    }

    @Test
    fun spooledBytesAreNotReadableFromDisk() {
        val store = SpoolBodyStore.open(root)
        val secret = "authorization: Bearer super-secret-token"

        store.put(secret.encodeUtf8())

        val onDisk = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).map(Files::readAllBytes).toList()
        }
        assertTrue(onDisk.isNotEmpty(), "expected the body to have been written somewhere")
        assertTrue(
            onDisk.none { String(it, Charsets.ISO_8859_1).contains(secret) },
            "the plaintext body was found on disk",
        )
        store.close()
    }

    @Test
    fun aReleasedBodyReadsEmptyRatherThanThrowing() {
        val store = SpoolBodyStore.open(root)
        val ref = assertNotNull(store.put(payload(5_000).toByteString()))

        store.release(listOf(ref))

        assertContentEquals(ByteArray(0), store.read(ref, 0, 100))
        assertEquals(okio.ByteString.EMPTY, store.readAtMost(ref, 100))
        store.close()
    }

    @Test
    fun readingAHandleTheStoreNeverIssuedIsEmpty() {
        val store = SpoolBodyStore.open(root)

        assertContentEquals(ByteArray(0), store.read(BodyRef("nobody", 128), 0, 128))

        store.close()
    }

    @Test
    fun theSessionDirectoryGoesAwayWithTheStore() {
        val store = SpoolBodyStore.open(root)
        store.put(payload(1_000).toByteString())
        val session = assertNotNull(sessionDirectories().singleOrNull())

        store.close()

        assertFalse(Files.exists(session), "the session spool outlived the store that owned it")
    }

    /**
     * A daemon that was killed cannot clean up after itself, so the next one does it. The leftovers are
     * unreadable by then anyway — their key died with the process — but they are still occupying a disk.
     */
    @Test
    fun openingClearsWhatACrashedDaemonLeftBehind() {
        val crashed = SpoolBodyStore.open(root)
        crashed.put(payload(1_000).toByteString())
        val abandoned = assertNotNull(sessionDirectories().singleOrNull())

        val fresh = SpoolBodyStore.open(root)

        assertFalse(Files.exists(abandoned), "a previous daemon's spool survived a fresh start")
        assertEquals(1, sessionDirectories().size)
        fresh.close()
    }

    @Test
    fun streamingReadsTheWholeBodyWithoutHoldingIt() {
        val store = SpoolBodyStore.open(root)
        val body = payload(300_000)
        val ref = assertNotNull(store.put(body.toByteString()))

        val streamed = store.open(ref).use { it.readBytes() }

        assertContentEquals(body, streamed)
        store.close()
    }

    private fun sessionDirectories(): List<Path> =
        Files.newDirectoryStream(root).use { entries -> entries.filter(Files::isDirectory) }

    private companion object {
        // Deliberately not random: a repeating pattern makes an off-by-one in the chunk math show up as a
        // shifted comparison rather than as noise that happens to differ.
        fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }
    }
}
