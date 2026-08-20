package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.host.HostSeed
import com.venbiasa.wailo.shared.SeedLayoutCodec
import com.venbiasa.wailo.shared.SeedNode
import com.venbiasa.wailo.shared.SeedRuleDef
import com.venbiasa.wailo.shared.allRules
import com.venbiasa.wailo.shared.settings.createKeyValueStore
import java.io.File

/**
 * Studio's authoring copy of the Seed layout (groups + rules + order), mirroring [MapLocalStore]: the
 * definitions ride in prefs as one string via the portable [SeedLayoutCodec], while each seed's authored
 * body is an app-managed file keyed by seed id, so prefs never holds bytes.
 *
 * The daemon holds the copy that actually answers holds — flattened, bodies inline — because a seed is
 * spent with or without a window open (ADR-0067). This store keeps the grouping and the editable files
 * that projection is built from. The armed queue is in neither: it is session state (ADR-0041).
 */
object SeedStore {
    private const val KEY = "seedRules"
    // The feature master (ADR-0030). Defaults on so a fresh install behaves like every other panel; with
    // no seeds authored, on and off are indistinguishable anyway.
    private const val ENABLED_KEY = "seedsEnabled"
    private val store = createKeyValueStore("desktop")

    fun load(): List<SeedNode> = SeedLayoutCodec.decode(store.getString(KEY, ""))

    fun save(nodes: List<SeedNode>) = store.putString(KEY, SeedLayoutCodec.encode(nodes))

    fun loadEnabled(): Boolean = store.getBoolean(ENABLED_KEY, true)

    fun saveEnabled(enabled: Boolean) = store.putBoolean(ENABLED_KEY, enabled)

    /**
     * Deletes the body of every seed present in [old] but gone from [new]. The panel's single mutation is
     * a whole-layout replace, which carries no per-rule delete signal, so the removed set is recovered by
     * diffing — sweeping bodies orphaned by a seed delete or a group delete-all.
     */
    fun reconcileRemovedBodies(old: List<SeedNode>, new: List<SeedNode>) {
        val kept = new.allRules().mapTo(HashSet()) { it.id }
        old.allRules().forEach { if (it.id !in kept) deleteBody(it.id) }
    }

    /** A seed's authored body bytes (empty if none saved yet or unreadable). */
    fun loadBody(seed: SeedRuleDef): ByteArray = loadBodyOrNull(seed) ?: ByteArray(0)

    /**
     * The same bytes, with "no body on disk" kept distinct from "an empty body". The daemon needs the
     * difference: a seed whose file has gone missing must decline the hold rather than answer it empty.
     */
    fun loadBodyOrNull(seed: SeedRuleDef): ByteArray? {
        val file = existingBodyFile(seed.id) ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Persists a seed's body to its app-managed file. The extension follows the seed's Content-Type so
     * the stored body is self-describing; any prior body for this id is removed first, so a body-type
     * switch (JSON → image) never leaves two files.
     */
    fun saveBody(seed: SeedRuleDef, bytes: ByteArray) {
        deleteBody(seed.id)
        runCatching { bodyFileFor(seed).writeBytes(bytes) }
    }

    /** Copies [bytes] in as [id]'s body under [contentType] — the Map Local import's body hand-off. */
    fun importBody(id: String, contentType: String?, bytes: ByteArray) {
        deleteBody(id)
        runCatching { File(seedBodiesDir(), "$id.${extensionForContentType(contentType)}").writeBytes(bytes) }
    }

    /** Lands a seed authored on another frontend, whose bytes arrive inline from the daemon. */
    fun importHostBody(seed: HostSeed) {
        if (!seed.bodyAvailable) return
        val contentType = seed.headers
            .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
            ?.value_
        importBody(seed.id, contentType, seed.bodyCopy())
    }

    fun deleteBody(id: String) {
        runCatching { managedBodyFiles(seedBodiesDir(), id).forEach { it.delete() } }
    }
}

private fun bodyFileFor(seed: SeedRuleDef): File =
    File(seedBodiesDir(), "${seed.id}.${extensionForContentType(seed.contentType())}")

private fun existingBodyFile(id: String): File? = managedBodyFiles(seedBodiesDir(), id).firstOrNull()

private fun SeedRuleDef.contentType(): String? =
    headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

private fun seedBodiesDir(): File = File(appDataDir(), "seed-bodies").apply { mkdirs() }
