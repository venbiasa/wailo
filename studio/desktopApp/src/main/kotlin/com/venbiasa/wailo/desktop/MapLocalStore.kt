package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.engine.ServedBody
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

    /**
     * The bytes this rule actually serves, from whichever source it uses — the same resolution [serveBody]
     * does. Used when copying a rule elsewhere (the Seed import, ADR-0041), where reading only the managed
     * file would silently copy nothing for a legacy rule that still points at a user-chosen path.
     */
    fun loadServedBody(rule: MapLocalRuleDef): ByteArray {
        return loadServedBodyOrNull(rule) ?: ByteArray(0)
    }

    fun loadServedBodyOrNull(rule: MapLocalRuleDef): ByteArray? =
        servedBodyFile(rule)?.let { file -> runCatching { file.readBytes() }.getOrNull() }
}

// Inline bodies live under the OS's per-user app-data dir (see [appDataDir]), one file per rule id, its
// extension following the rule's Content-Type.
private fun managedBodyFileFor(rule: MapLocalRuleDef): File =
    File(mapLocalBodiesDir(), "${rule.id}.${extensionForContentType(rule.contentType())}")

private fun managedBodyFiles(id: String): List<File> = managedBodyFiles(mapLocalBodiesDir(), id)

private fun existingManagedBodyFile(id: String): File? = managedBodyFiles(id).firstOrNull()

// Where a rule's served bytes come from: its app-managed file (every rule authored since bodies became
// app-managed) or the user-chosen path a legacy file-source rule still points at.
private fun servedBodyFile(rule: MapLocalRuleDef): File? =
    if (rule.inline) existingManagedBodyFile(rule.id) else File(rule.filePath)

private fun MapLocalRuleDef.contentType(): String? =
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

private fun mapLocalBodiesDir(): File = File(appDataDir(), "maplocal-bodies").apply { mkdirs() }

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
    val file = servedBodyFile(def) ?: return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return ServedBody(code = def.statusCode, headers = servedHeaders(def.headers, file, bytes), body = bytes)
}
