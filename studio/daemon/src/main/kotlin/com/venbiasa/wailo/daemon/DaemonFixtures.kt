package com.venbiasa.wailo.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Daemon-owned configuration that must survive a stop, crash, or replace (ADR-0061). Traffic and
 * holds stay in memory. [nodes] carries the grouping too, since the daemon owns it now (ADR-0081).
 *
 * [rules] is the pre-groups file: a flat list that sat beside an opaque Studio blob the daemon could not
 * read. It is still parsed so an upgrade does not read as an empty configuration and wipe the rules on
 * the next write. They come back as loose rules; their grouping returns when Studio next attaches, since
 * its own prefs still hold the layout that blob was encoded from.
 */
@Serializable
internal data class PersistedMapLocal(
    val enabled: Boolean = true,
    val nodes: List<DaemonRuleNode<MapLocalRuleDto>> = emptyList(),
    val rules: List<MapLocalRuleDto> = emptyList(),
) {
    fun resolvedNodes(): List<DaemonRuleNode<MapLocalRuleDto>> =
        nodes.ifEmpty { rules.map { DaemonRuleNode(rules = listOf(it)) } }
}

@Serializable
internal data class PersistedBreakpoints(
    val enabled: Boolean = true,
    val nodes: List<DaemonRuleNode<BreakpointRuleDto>> = emptyList(),
    val rules: List<BreakpointRuleDto> = emptyList(),
) {
    fun resolvedNodes(): List<DaemonRuleNode<BreakpointRuleDto>> =
        nodes.ifEmpty { rules.map { DaemonRuleNode(rules = listOf(it)) } }
}

/**
 * Seeds are configuration like the two above — authored fixtures that must outlive the window that
 * wrote them (ADR-0067). The *armed queue* is not here on purpose: it is a position in a run, so it
 * stays session state beside traffic and holds (ADR-0041/0061).
 */
@Serializable
internal data class PersistedSeeds(
    val enabled: Boolean = true,
    val nodes: List<DaemonRuleNode<SeedRuleDto>> = emptyList(),
    val rules: List<SeedRuleDto> = emptyList(),
) {
    fun resolvedNodes(): List<DaemonRuleNode<SeedRuleDto>> =
        nodes.ifEmpty { rules.map { DaemonRuleNode(rules = listOf(it)) } }
}

/**
 * The filter as authored, so the feature master and each list's armed state survive a restart separately.
 * Storing only the folded per-list flags could not tell "master off, list armed" from "master on, list not
 * armed", and a frontend had to guess the master back from them — which lost it on every launch (ADR-0082).
 */
@Serializable
internal data class PersistedCaptureFilter(
    // On by default so a file written before the master was daemon state reads back the way it behaved:
    // live, governed by the two list switches alone.
    val masterEnabled: Boolean = true,
    val allowlistEnabled: Boolean = false,
    val allowPatterns: List<String> = emptyList(),
    val blocklistEnabled: Boolean = false,
    val blockPatterns: List<String> = emptyList(),
)

/**
 * The hosts the user marked as worth watching. Configuration rather than session state — a bookmark
 * outlives the traffic that prompted it — and daemon-owned so a headless CLI or MCP session can read
 * which hosts the user actually cares about instead of that being visible only behind a window
 * (ADR-0084). [hosts] keeps authoring order, which is the order the list is shown in.
 */
@Serializable
internal data class PersistedBookmarks(
    val hosts: List<String> = emptyList(),
)

internal class DaemonFixturesStore(
    internal val directory: Path = wailoStateDir(),
) {
    private val mapLocalPath: Path get() = directory.resolve("map-local.json")
    private val breakpointsPath: Path get() = directory.resolve("breakpoints.json")
    private val seedsPath: Path get() = directory.resolve("seeds.json")
    private val captureFilterPath: Path get() = directory.resolve("capture-filter.json")
    private val bookmarksPath: Path get() = directory.resolve("bookmarks.json")

    @Synchronized
    fun loadMapLocal(): PersistedMapLocal = read(mapLocalPath, PersistedMapLocal())

    @Synchronized
    fun loadMapLocalIfPresent(): PersistedMapLocal? =
        if (Files.isRegularFile(mapLocalPath)) loadMapLocal() else null

    @Synchronized
    fun saveMapLocal(value: PersistedMapLocal) = write(mapLocalPath, DaemonJson.encodeToString(value))

    @Synchronized
    fun loadBreakpoints(): PersistedBreakpoints = read(breakpointsPath, PersistedBreakpoints())

    @Synchronized
    fun loadBreakpointsIfPresent(): PersistedBreakpoints? =
        if (Files.isRegularFile(breakpointsPath)) loadBreakpoints() else null

    @Synchronized
    fun saveBreakpoints(value: PersistedBreakpoints) = write(breakpointsPath, DaemonJson.encodeToString(value))

    @Synchronized
    fun loadSeeds(): PersistedSeeds = read(seedsPath, PersistedSeeds())

    @Synchronized
    fun loadSeedsIfPresent(): PersistedSeeds? =
        if (Files.isRegularFile(seedsPath)) loadSeeds() else null

    @Synchronized
    fun saveSeeds(value: PersistedSeeds) = write(seedsPath, DaemonJson.encodeToString(value))

    @Synchronized
    fun loadCaptureFilter(): PersistedCaptureFilter = read(captureFilterPath, PersistedCaptureFilter())

    @Synchronized
    fun loadCaptureFilterIfPresent(): PersistedCaptureFilter? =
        if (Files.isRegularFile(captureFilterPath)) loadCaptureFilter() else null

    @Synchronized
    fun saveCaptureFilter(value: PersistedCaptureFilter) =
        write(captureFilterPath, DaemonJson.encodeToString(value))

    @Synchronized
    fun loadBookmarks(): PersistedBookmarks = read(bookmarksPath, PersistedBookmarks())

    @Synchronized
    fun saveBookmarks(value: PersistedBookmarks) = write(bookmarksPath, DaemonJson.encodeToString(value))

    private inline fun <reified T> read(path: Path, fallback: T): T {
        if (!Files.isRegularFile(path)) return fallback
        return runCatching {
            DaemonJson.decodeFromString<T>(Files.readString(path))
        }.getOrDefault(fallback)
    }

    private fun write(path: Path, json: String) {
        Files.createDirectories(path.parent)
        setOwnerOnly(path.parent, directory = true)
        val temporary = Files.createTempFile(path.parent, ".fixtures-", ".tmp")
        try {
            Files.writeString(temporary, json)
            setOwnerOnly(temporary, directory = false)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
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
}
