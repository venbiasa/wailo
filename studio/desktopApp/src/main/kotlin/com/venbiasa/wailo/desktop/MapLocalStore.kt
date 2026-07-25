package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.ServedBody
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.shared.MapLocalLayoutCodec
import com.venbiasa.wailo.shared.MapLocalNode
import com.venbiasa.wailo.shared.MapLocalRuleDef
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.findRule
import com.venbiasa.wailo.shared.isRuleActive
import com.venbiasa.wailo.shared.rulesForMatch
import com.venbiasa.wailo.shared.settings.createKeyValueStore
import java.io.File

/**
 * Persists the Map Local layout (groups + rules + order) across launches, mirroring [BookmarkStore].
 * Host-owned: the layout lives at the desktop window; `shared` only renders it and calls back to mutate.
 * The layout *definitions* ride in prefs as one string via the portable [MapLocalLayoutCodec]; an inline
 * rule's authored body is stored separately as an app-managed file keyed by rule id (see
 * [loadInlineBody]/[saveInlineBody]), so the prefs line never holds bytes and a matched request always
 * reads the body fresh from disk (ADR-0019).
 */
object MapLocalStore {
    private const val KEY = "mapLocalRules"
    // The feature master (ADR-0030); defaults on so an install with no saved value keeps serving rules
    // exactly as before the switch existed. Gates only what the host pushes, never the saved layout.
    private const val ENABLED_KEY = "mapLocalEnabled"
    private val store = createKeyValueStore("desktop")

    fun load(): List<MapLocalNode> = MapLocalLayoutCodec.decode(store.getString(KEY, ""))

    fun save(nodes: List<MapLocalNode>) = store.putString(KEY, MapLocalLayoutCodec.encode(nodes))

    fun loadEnabled(): Boolean = store.getBoolean(ENABLED_KEY, true)

    fun saveEnabled(enabled: Boolean) = store.putBoolean(ENABLED_KEY, enabled)

    /**
     * Deletes the app-managed body of every rule present in [old] but gone from [new]. A whole-layout
     * replace (the single mutation the panel uses) hands us no per-rule delete signal, so the removed set
     * is recovered by diffing — this sweeps bodies orphaned by a rule delete or a group delete-all.
     */
    fun reconcileRemovedBodies(old: List<MapLocalNode>, new: List<MapLocalNode>) {
        val kept = new.allRules().mapTo(HashSet()) { it.id }
        old.allRules().forEach { if (it.id !in kept) deleteInlineBody(it.id) }
    }

    /**
     * An inline rule's authored body bytes (empty if none saved yet or unreadable). Bytes, not text, so
     * a rule can serve any payload — JSON or a binary image — through the one code path.
     */
    fun loadInlineBody(rule: MapLocalRuleDef): ByteArray {
        val file = existingManagedBodyFile(rule.id) ?: return ByteArray(0)
        return runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
    }

    /**
     * Persists an inline rule's body to its app-managed file, so [serveBody] can read it per request.
     * The file's extension follows the rule's Content-Type (json/png/…) so the stored body is
     * self-describing and the extension guess is still correct if the header is later cleared. Any prior
     * body for this id is removed first, so a body-type switch (e.g. JSON → image) never leaves two files.
     */
    fun saveInlineBody(rule: MapLocalRuleDef, bytes: ByteArray) {
        deleteInlineBody(rule.id)
        runCatching { managedBodyFileFor(rule).writeBytes(bytes) }
    }

    /** Removes any app-managed body file(s) for [id] (on rule delete, a body-type switch, or a file source). */
    fun deleteInlineBody(id: String) {
        runCatching { managedBodyFiles(id).forEach { it.delete() } }
    }
}

// Inline bodies live under the OS's per-user app-data dir (not in prefs, which is for small values),
// one file per rule id. The extension follows the rule's Content-Type so the stored body is
// self-describing (a .png holds an image, .json holds JSON) and drives the served Content-Type when
// the rule sets none.
private fun managedBodyFileFor(rule: MapLocalRuleDef): File =
    File(mapLocalBodiesDir(), "${rule.id}.${extensionForContentType(rule.contentType())}")

// Every managed body file for a rule id (there should be at most one). A glob rather than a fixed name
// so it finds the body whatever its extension, sweeps a stale file left by an interrupted type switch,
// and still finds the legacy fixed-name ".json" body written before bodies were typed. The trailing dot
// makes "$id." delimit the id, so sibling ids that share a prefix never match.
private fun managedBodyFiles(id: String): List<File> =
    mapLocalBodiesDir().listFiles { file -> file.name.startsWith("$id.") }?.toList() ?: emptyList()

private fun existingManagedBodyFile(id: String): File? = managedBodyFiles(id).firstOrNull()

private fun MapLocalRuleDef.contentType(): String? =
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

// The file extension to store a body under, from its Content-Type — the inverse of [guessContentType],
// used to name the managed body file. Unknown/absent types fall back to a neutral ".bin".
private fun extensionForContentType(contentType: String?): String =
    when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "application/json" -> "json"
        "text/html" -> "html"
        "application/xml", "text/xml" -> "xml"
        "text/plain" -> "txt"
        "application/javascript", "text/javascript" -> "js"
        "text/css" -> "css"
        "text/csv" -> "csv"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        else -> "bin"
    }

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
 * Compiles the authored layout into the match-metadata the engine pushes to devices (ADR-0019): the
 * *active* rules (group-on AND rule-on) in top-to-bottom priority order (ADR-0026), with no file reading
 * and no bytes — the device caches only how to *match* and returns the first match in list order, so this
 * order is the priority. The body is read later, per match, by [serveBody].
 */
fun compileRules(nodes: List<MapLocalNode>): List<MapLocalRule> =
    nodes.rulesForMatch().map { def ->
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
 * Returns null (→ found=false → device falls open to the network) when the rule is gone, its group is
 * off, the rule is off, or its file can't be read. The rule's authored headers pass through as-is,
 * except Content-Length (the host owns it, computed from the bytes); Content-Type falls back to an
 * extension guess when unset.
 */
fun serveBody(ruleId: String, nodes: List<MapLocalNode>): ServedBody? {
    if (!nodes.isRuleActive(ruleId)) return null
    val def = nodes.findRule(ruleId) ?: return null
    val file = if (def.inline) (existingManagedBodyFile(def.id) ?: return null) else File(def.filePath)
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

internal fun guessContentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
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
