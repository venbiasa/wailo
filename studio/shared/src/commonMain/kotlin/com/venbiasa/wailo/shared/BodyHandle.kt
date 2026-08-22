package com.venbiasa.wailo.shared

import androidx.compose.runtime.staticCompositionLocalOf
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A reference to a captured body the daemon is holding, and how many bytes of it there are.
 *
 * Mirrors `engine.BodyRef` by value for the same reason [FlowEntry] mirrors `engine.CapturedExchange`:
 * `shared` is a sibling of `engine`, not a consumer of it, so the row is remapped at the `desktopApp`
 * boundary.
 */
data class BodyHandle(val id: String, val size: Long)

/**
 * Reads a captured body back, a range at a time.
 *
 * Bodies do not arrive with their rows (ADR-0069): a list of a thousand exchanges would otherwise be a
 * list of a thousand payloads, held in Studio's heap so that a few of them could eventually be looked
 * at. `desktopApp` supplies the implementation, because it is the side that can reach the daemon.
 */
fun interface BodyLoader {
    suspend fun load(handle: BodyHandle, offset: Long, length: Int): ByteArray
}

/**
 * The loader every body view resolves against. Defaults to reading nothing, which is the right answer
 * for a preview or a test rendered outside a running Studio — an empty body, not a crash.
 */
val LocalBodyLoader = staticCompositionLocalOf { BodyLoader { _, _, _ -> ByteArray(0) } }

/**
 * The most of a body any single Studio surface pulls into memory at once.
 *
 * Every caller that wants "the body" as one value — a preview, a cURL copy, a rule authored from a
 * captured response — meets this ceiling, because "the body" stopped being a bounded quantity once
 * capture stopped being bounded by RAM. Surfaces that must handle more than this do it by range instead.
 */
const val MAX_INLINE_BODY_BYTES: Int = 8 * 1024 * 1024

/** Read the first [limit] bytes behind [handle], or nothing when there is no body to read. */
suspend fun BodyLoader.prefix(handle: BodyHandle?, limit: Int = MAX_INLINE_BODY_BYTES): ByteString {
    if (handle == null || handle.size <= 0 || limit <= 0) return ByteString.EMPTY
    return load(handle, 0, minOf(handle.size, limit.toLong()).toInt()).toByteString()
}
