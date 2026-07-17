package com.venbiasa.wailo.shared

import kotlin.random.Random

/**
 * A Map Local rule as the desktop authors it: match a request, answer it with the contents of a
 * local file. This is the UI/host-facing definition — it holds the file *path*, not its bytes. The
 * host reads the file and compiles this into the protocol `MapLocalRule` (bytes inlined) before
 * pushing it to devices, so `shared` never touches the filesystem or the wire types.
 *
 * [urlPattern] is a wildcard match against the full request URL (`*` matches any run of characters).
 * [methods] restricts the rule to those HTTP methods; empty means any. [statusCode] and
 * [contentType] shape the synthesized response; a blank [contentType] is inferred from the file
 * extension by the host.
 */
data class MapLocalRuleDef(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String = "",
    val methods: List<String> = emptyList(),
    val filePath: String = "",
    val statusCode: Int = 200,
    val contentType: String = "",
) {
    companion object {
        /** A stable, unique id for a freshly authored rule (no java.* so commonMain stays portable). */
        fun newId(): String = "rule-" + Random.nextLong().toULong().toString(16).padStart(16, '0')

        /**
         * A sensible starting pattern when mapping a specific captured URL: drop the query string and
         * append `*`, so the rule matches the endpoint regardless of its (usually varying) query.
         */
        fun patternFor(url: String): String {
            val base = url.substringBefore('?').ifBlank { url }
            return if (base.endsWith('*')) base else "$base*"
        }
    }
}
