package com.venbiasa.wailo.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Daemon-owned configuration that must survive a stop, crash, or replace (ADR-0061). Traffic and
 * holds stay in memory. [layout] is Studio's grouped-rule codec, carried as an opaque blob because
 * `daemon` must not depend on `shared`. A full replace without [layout] drops it; upsert/remove keep it.
 */
@Serializable
internal data class PersistedMapLocal(
    val enabled: Boolean = true,
    val layout: String = "",
    val rules: List<MapLocalRuleDto> = emptyList(),
)

@Serializable
internal data class PersistedBreakpoints(
    val enabled: Boolean = true,
    val layout: String = "",
    val rules: List<BreakpointRuleDto> = emptyList(),
)

/**
 * Seeds are configuration like the two above — authored fixtures that must outlive the window that
 * wrote them (ADR-0067). The *armed queue* is not here on purpose: it is a position in a run, so it
 * stays session state beside traffic and holds (ADR-0041/0061).
 */
@Serializable
internal data class PersistedSeeds(
    val enabled: Boolean = true,
    val layout: String = "",
    val rules: List<SeedRuleDto> = emptyList(),
)

@Serializable
internal data class PersistedCaptureFilter(
    val allowlistEnabled: Boolean = false,
    val allowPatterns: List<String> = emptyList(),
    val blocklistEnabled: Boolean = false,
    val blockPatterns: List<String> = emptyList(),
)

internal class DaemonFixturesStore(
    private val directory: Path = wailoStateDir(),
) {
    private val mapLocalPath: Path get() = directory.resolve("map-local.json")
    private val breakpointsPath: Path get() = directory.resolve("breakpoints.json")
    private val seedsPath: Path get() = directory.resolve("seeds.json")
    private val captureFilterPath: Path get() = directory.resolve("capture-filter.json")

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
