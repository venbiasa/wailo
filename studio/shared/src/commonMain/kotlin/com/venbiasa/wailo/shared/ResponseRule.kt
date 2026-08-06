package com.venbiasa.wailo.shared

/**
 * One response header an authored rule serves back. A named type (not a raw pair) so the editors and
 * stores read clearly; the host materializes these into the wire `Header`s at serve time. Shared by
 * every feature that authors a response — Map Local's rules and Seed's canned answers both carry these.
 */
data class ResponseHeader(val name: String, val value: String)

/**
 * A body file the host's file picker read from disk, handed back to a response editor to author a
 * rule's response body: a JSON/text file loads into the code editor, an image into the preview.
 * [contentType] is inferred from the file's extension so the editor can set the rule's Content-Type,
 * which picks the body surface and keeps what's served in sync. Not a `data class`: a [ByteArray] has
 * identity equality, so the generated equals/hashCode would be misleading — this is a plain carrier the
 * editor reads once.
 */
class PickedFile(val bytes: ByteArray, val contentType: String)
