package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.ServedBody
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.settings.createKeyValueStore
import java.io.File
import java.util.Base64

/**
 * Persists Map Local rule definitions across launches, mirroring [BookmarkStore]. Host-owned: the
 * rule state lives at the desktop window; `shared` only renders it and calls back to mutate. Only the
 * rule *definitions* (including the file path) are stored — never the file bytes — so a later edit to
 * the file is picked up on the next compile/push.
 *
 * [createKeyValueStore] is primitive-only, so the list rides as one string: one rule per line, fields
 * `|`-separated, and each free-text field Base64-encoded so paths/patterns can never collide with the
 * delimiters.
 */
object MapLocalStore {
    private const val KEY = "mapLocalRules"
    private const val RULE_SEP = "\n"
    private const val FIELD_SEP = "|"
    private const val METHOD_SEP = ","
    private val store = createKeyValueStore("desktop")
    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    fun load(): List<MapLocalRuleDef> =
        store.getString(KEY, "")
            .split(RULE_SEP)
            .mapNotNull { decode(it) }

    fun save(rules: List<MapLocalRuleDef>) =
        store.putString(KEY, rules.joinToString(RULE_SEP) { encode(it) })

    private fun encode(rule: MapLocalRuleDef): String = listOf(
        rule.id,
        if (rule.enabled) "1" else "0",
        enc(rule.urlPattern),
        enc(rule.methods.joinToString(METHOD_SEP)),
        enc(rule.filePath),
        rule.statusCode.toString(),
        enc(rule.contentType),
    ).joinToString(FIELD_SEP)

    private fun decode(line: String): MapLocalRuleDef? {
        val parts = line.split(FIELD_SEP)
        if (parts.size != 7) return null
        return MapLocalRuleDef(
            id = parts[0],
            enabled = parts[1] == "1",
            urlPattern = dec(parts[2]),
            methods = dec(parts[3]).split(METHOD_SEP).map { it.trim() }.filter { it.isNotEmpty() },
            filePath = dec(parts[4]),
            statusCode = parts[5].toIntOrNull() ?: 200,
            contentType = dec(parts[6]),
        )
    }

    private fun enc(value: String): String = b64Encoder.encodeToString(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { b64Decoder.decode(value).decodeToString() }.getOrDefault("")
}

/**
 * Compiles the authored rules into the match-metadata the engine pushes to devices (ADR-0019): the
 * enabled ones, with no file reading and no bytes — the device caches only how to *match*. The body is
 * read later, per match, by [serveBody]. A file that can't be read isn't dropped here (it's not read
 * yet); a broken path surfaces at fetch time as found=false, and the device falls open to the network.
 */
fun compileRules(rules: List<MapLocalRuleDef>): List<MapLocalRule> =
    rules.filter { it.enabled }.map { def ->
        MapLocalRule(
            id = def.id,
            enabled = true,
            url_pattern = def.urlPattern,
            methods = def.methods,
        )
    }

/**
 * Resolves a matched rule to the bytes to serve, read fresh from disk at request time. Returns null
 * (→ found=false → device falls open to the network) when the rule is gone/disabled or its file can't
 * be read. Content-Type is the explicit one, else inferred from the extension.
 */
fun serveBody(ruleId: String, defs: List<MapLocalRuleDef>): ServedBody? {
    val def = defs.firstOrNull { it.id == ruleId && it.enabled } ?: return null
    val bytes = runCatching { File(def.filePath).readBytes() }.getOrNull() ?: return null
    val contentType = def.contentType.ifBlank { guessContentType(def.filePath) }
    val headers = buildList {
        if (contentType.isNotBlank()) add(Header(name = "Content-Type", value_ = contentType))
        add(Header(name = "Content-Length", value_ = bytes.size.toString()))
    }
    return ServedBody(code = def.statusCode, headers = headers, body = bytes)
}

private fun guessContentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml" -> "application/xml"
    "txt" -> "text/plain"
    "js" -> "application/javascript"
    "css" -> "text/css"
    "csv" -> "text/csv"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}
