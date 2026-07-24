package com.venbiasa.wailo.shared

import kotlin.random.Random

/**
 * One response header a Map Local rule serves back. A named type (not a raw pair) so the editor and
 * store read clearly; the host materializes these into the wire `Header`s at serve time.
 */
data class MapLocalHeader(val name: String, val value: String)

/**
 * A body file the host's file picker read from disk, handed back to the Map Local editor to author a
 * rule's response body: a JSON/text file loads into the code editor, an image into the preview.
 * [contentType] is inferred from the file's extension so the editor can set the rule's Content-Type,
 * which picks the body surface and keeps what's served in sync. Not a `data class`: a [ByteArray] has
 * identity equality, so the generated equals/hashCode would be misleading — this is a plain carrier the
 * editor reads once.
 */
class PickedFile(val bytes: ByteArray, val contentType: String)

/**
 * A Map Local rule as the desktop authors it: match a request, answer it with the contents of a
 * local file. This is the UI/host-facing definition — it holds the file *path*, not its bytes. The
 * host reads the file and compiles this into the protocol `MapLocalRule` (bytes inlined) before
 * pushing it to devices, so `shared` never touches the filesystem or the wire types.
 *
 * [name] is a human label for the rule (what the list shows and how the author identifies it); it
 * defaults to "Untitled" for a fresh rule and must be non-blank to save. It is not part of matching.
 * [urlPattern] is a wildcard match against the full request URL (`*` matches any run of characters).
 * [method] restricts the rule to that single HTTP method; blank means any. [statusCode] and [headers]
 * shape the synthesized response; [headers] carries Content-Type (there is no separate field for it) —
 * if none is set the host infers Content-Type from the file extension, and it always sets Content-Length
 * from the served bytes, so a hand-entered length is ignored.
 *
 * A rule serves its body one of two ways ([inline]): when false, from the user's own file at
 * [filePath] (read fresh per request, so external edits are picked up); when true, from a body
 * authored in the desktop's editor, which the host persists to an app-managed file keyed by [id].
 * Either way the served bytes come from a file on disk at request time (ADR-0019 unchanged); the flag
 * only tells the UI which surface to show and the host where the bytes live.
 */
data class MapLocalRuleDef(
    override val id: String,
    val name: String = "Untitled",
    override val enabled: Boolean = true,
    val urlPattern: String = "",
    val method: String = "",
    val filePath: String = "",
    val statusCode: Int = 200,
    val headers: List<MapLocalHeader> = emptyList(),
    val inline: Boolean = false,
) : LayoutRule<MapLocalRuleDef> {
    override fun withEnabled(enabled: Boolean): MapLocalRuleDef = copy(enabled = enabled)

    companion object {
        /** A stable, unique id for a freshly authored rule (no java.* so commonMain stays portable). */
        fun newId(): String = "rule-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}
