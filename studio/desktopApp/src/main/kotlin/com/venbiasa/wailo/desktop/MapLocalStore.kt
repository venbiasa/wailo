package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.ServedBody
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.shared.MapLocalHeader
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.settings.createKeyValueStore
import java.io.File
import java.util.Base64

/**
 * Persists Map Local rule definitions across launches, mirroring [BookmarkStore]. Host-owned: the
 * rule state lives at the desktop window; `shared` only renders it and calls back to mutate. The rule
 * *definitions* ride in prefs; an inline rule's authored body is stored separately as an app-managed
 * file keyed by rule id (see [loadInlineBody]/[saveInlineBody]), so the prefs line never holds bytes
 * and a matched request always reads the body fresh from disk (ADR-0019).
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
    // Within the single headers field: one header is enc(name):enc(value), headers joined by ",". Base64
    // has neither ":" nor ",", so a header value can hold any bytes without colliding with a delimiter.
    private const val HEADER_SEP = ","
    private const val HEADER_KV = ":"
    private val store = createKeyValueStore("desktop")
    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    fun load(): List<MapLocalRuleDef> =
        store.getString(KEY, "")
            .split(RULE_SEP)
            .mapNotNull { decode(it) }

    fun save(rules: List<MapLocalRuleDef>) =
        store.putString(KEY, rules.joinToString(RULE_SEP) { encode(it) })

    /** The current text of an inline rule's authored body (empty if none saved yet or unreadable). */
    fun loadInlineBody(rule: MapLocalRuleDef): String =
        runCatching { managedBodyFile(rule.id).readText() }.getOrDefault("")

    /** Persists an inline rule's body to its app-managed file, so [serveBody] can read it per request. */
    fun saveInlineBody(rule: MapLocalRuleDef, text: String) {
        runCatching { managedBodyFile(rule.id).writeText(text) }
    }

    /** Removes an inline rule's managed body file (on rule delete or a switch back to a file source). */
    fun deleteInlineBody(id: String) {
        runCatching { managedBodyFile(id).delete() }
    }

    private fun encode(rule: MapLocalRuleDef): String = listOf(
        rule.id,
        if (rule.enabled) "1" else "0",
        enc(rule.urlPattern),
        enc(rule.method),
        enc(rule.filePath),
        rule.statusCode.toString(),
        encodeHeaders(rule.headers),
        if (rule.inline) "1" else "0",
    ).joinToString(FIELD_SEP)

    private fun decode(line: String): MapLocalRuleDef? {
        val parts = line.split(FIELD_SEP)
        // Accept the legacy 7-field layout (pre-inline rules) as file-backed, plus the current 8-field one.
        if (parts.size != 7 && parts.size != 8) return null
        return MapLocalRuleDef(
            id = parts[0],
            enabled = parts[1] == "1",
            urlPattern = dec(parts[2]),
            // A rule now matches a single method; a legacy multi-method line collapses to its first.
            method = dec(parts[3]).substringBefore(METHOD_SEP).trim(),
            filePath = dec(parts[4]),
            statusCode = parts[5].toIntOrNull() ?: 200,
            headers = decodeHeaders(parts[6]),
            inline = parts.size == 8 && parts[7] == "1",
        )
    }

    private fun encodeHeaders(headers: List<MapLocalHeader>): String =
        headers.filter { it.name.isNotBlank() }
            .joinToString(HEADER_SEP) { enc(it.name) + HEADER_KV + enc(it.value) }

    // Field[6] once held a lone Base64 Content-Type; a Base64 token has no ":" separator, so its absence
    // marks a legacy line — migrate it to a Content-Type header. A blank field means no headers.
    private fun decodeHeaders(field: String): List<MapLocalHeader> {
        if (field.isBlank()) return emptyList()
        if (!field.contains(HEADER_KV)) return listOf(MapLocalHeader("Content-Type", dec(field)))
        return field.split(HEADER_SEP).mapNotNull { part ->
            val kv = part.split(HEADER_KV)
            if (kv.size != 2) null else MapLocalHeader(dec(kv[0]), dec(kv[1]))
        }
    }

    private fun enc(value: String): String = b64Encoder.encodeToString(value.encodeToByteArray())
    private fun dec(value: String): String = runCatching { b64Decoder.decode(value).decodeToString() }.getOrDefault("")
}

// Inline bodies live under the OS's per-user app-data dir (not in prefs, which is for small values),
// one .json file per rule id. The .json suffix also drives the served Content-Type when none is set.
private fun managedBodyFile(id: String): File = File(mapLocalBodiesDir(), "$id.json")

private fun mapLocalBodiesDir(): File = File(appDataDir(), "maplocal-bodies").apply { mkdirs() }

private fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("win") -> (System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home)).let { File(it, "Wailo") }
        os.contains("mac") -> File(home, "Library/Application Support/Wailo")
        else -> {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, ".local/share")
            File(base, "Wailo")
        }
    }
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
            // The wire type keeps a list; a blank single method means "match any method".
            methods = if (def.method.isBlank()) emptyList() else listOf(def.method),
        )
    }

/**
 * Resolves a matched rule to the bytes to serve, read fresh from disk at request time. The body comes
 * from the rule's app-managed file (inline rules) or the user's chosen file (file rules) — either way
 * a path read at request time, so ADR-0019 (device holds no bodies; desktop reads fresh) is unchanged.
 * Returns null (→ found=false → device falls open to the network) when the rule is gone/disabled or
 * its file can't be read. The rule's authored headers pass through as-is, except Content-Length (the
 * host owns it, computed from the bytes); Content-Type falls back to an extension guess when unset.
 */
fun serveBody(ruleId: String, defs: List<MapLocalRuleDef>): ServedBody? {
    val def = defs.firstOrNull { it.id == ruleId && it.enabled } ?: return null
    val file = if (def.inline) managedBodyFile(def.id) else File(def.filePath)
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val authored = def.headers.filter { it.name.isNotBlank() }
    val hasContentType = authored.any { it.name.equals("Content-Type", ignoreCase = true) }
    val headers = buildList {
        // User headers pass through, minus Content-Length: the host recomputes it so it can't drift from
        // the served bytes (a stale length would truncate or hang the response).
        authored.forEach { header ->
            if (!header.name.equals("Content-Length", ignoreCase = true)) {
                add(Header(name = header.name, value_ = header.value))
            }
        }
        if (!hasContentType) {
            val guessed = guessContentType(file.name)
            if (guessed.isNotBlank()) add(Header(name = "Content-Type", value_ = guessed))
        }
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
