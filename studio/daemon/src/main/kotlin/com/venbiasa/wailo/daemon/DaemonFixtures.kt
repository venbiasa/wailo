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
