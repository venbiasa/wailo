package com.venbiasa.wailo.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * The bytes behind Map Local fixtures and seeds: resident in the daemon, persisted one file per rule.
 *
 * They used to ride inline on the rule, which put every fixture in the set inside `map-local.json` and
 * inside every publish and poll — so flipping one rule's switch rewrote and re-sent all of them. The
 * layout now names a body by size and digest and the bytes live here (ADR-0086).
 *
 * Resident rather than read per match: a Map Local rule serves on the device and relay paths
 * synchronously, and a body that had to be fetched there could fail to arrive — reintroducing the
 * "this rule's body did not load" state ADR-0085 retired. A file is where a body persists, never where
 * it is looked up.
 */
internal class DaemonRuleBodyStore(private val directory: Path) {
    private val resident = ConcurrentHashMap<String, ByteArray>()

    /** The rule's bytes, or empty — which is also what an authored empty body reads as (ADR-0085). */
    fun body(family: String, id: String): ByteArray = resident[key(family, id)] ?: EMPTY

    fun put(family: String, id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return remove(family, id)
        resident[key(family, id)] = bytes
        runCatching { write(pathFor(family, id), bytes) }
    }

    fun remove(family: String, id: String) {
        resident.remove(key(family, id))
        runCatching { Files.deleteIfExists(pathFor(family, id)) }
    }

    /** Pull the named rules' bodies into memory, on the way back up from a restart. */
    fun load(family: String, ids: Collection<String>) {
        ids.forEach { id ->
            val bytes = runCatching { Files.readAllBytes(pathFor(family, id)) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) resident[key(family, id)] = bytes
        }
    }

    /**
     * Forget every body in [family] that no rule in [ids] claims. Called after a layout is published, so
     * a deleted rule does not leave its fixture on disk forever, and at startup, so one written by a
     * daemon that died mid-edit is collected rather than lingering unreferenced.
     */
    fun retain(family: String, ids: Collection<String>) {
        val keep = ids.toSet()
        val keepFiles = keep.mapTo(mutableSetOf(), ::fileName)
        runCatching {
            Files.list(familyDir(family)).use { entries ->
                entries.filter { it.fileName.toString() !in keepFiles }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        val prefix = "$family/"
        resident.keys.removeIf { it.startsWith(prefix) && it.removePrefix(prefix) !in keep }
    }

    private fun key(family: String, id: String) = "$family/$id"

    private fun familyDir(family: String): Path = directory.resolve("rule-bodies").resolve(family)

    private fun pathFor(family: String, id: String): Path = familyDir(family).resolve(fileName(id))

    /**
     * A rule id digested, never spelled out. Ids come from MCP and CLI callers as well as the panel, so a
     * literal name would let one containing `../` address a path outside the state directory — and two
     * ids differing only in case would silently share a file on a case-insensitive volume.
     */
    private fun fileName(id: String): String = sha256(id.toByteArray(Charsets.UTF_8))

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        setOwnerOnly(path.parent, directory = true)
        val temporary = Files.createTempFile(path.parent, ".body-", ".tmp")
        try {
            Files.write(temporary, bytes)
            setOwnerOnly(temporary, directory = false)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun setOwnerOnly(target: Path, directory: Boolean) {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(target, permissions) }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * What a rule carries in place of its bytes. Empty for an empty body, so "nothing authored" stays absent
 * from the layout rather than becoming the digest of zero bytes.
 *
 * It is not decoration: the poll withholds a rule family whose hash the client already has, and once
 * bytes leave the DTO a body-only edit would otherwise produce an identical layout and never be
 * delivered. It is also how an author knows the daemon took the bytes it staged, so a frontend computes
 * it over its own copy and compares — which is why this is not internal to the daemon.
 */
fun bodyDigest(bytes: ByteArray): String =
    if (bytes.isEmpty()) "" else sha256(bytes)

/**
 * A body written inline by an older build. Unreadable base64 reads as no body rather than failing the
 * restore: the alternative is a daemon that will not start because one fixture is corrupt.
 */
internal fun String.decodeInlineBody(): ByteArray =
    runCatching { decodeBase64() }.getOrDefault(ByteArray(0))

/** The bodies a publish is changing, by rule id (ADR-0086). */
internal fun Map<String, String>.decodeBodies(): Map<String, ByteArray> =
    mapValues { (_, base64) -> base64.decodeInlineBody() }

internal fun Map<String, ByteArray>.encodeBodies(): Map<String, String> =
    mapValues { (_, bytes) -> bytes.encodeBase64() }

private fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
