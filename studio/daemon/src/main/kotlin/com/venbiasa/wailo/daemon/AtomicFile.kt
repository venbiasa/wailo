package com.venbiasa.wailo.daemon

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal fun writeAtomically(path: Path, content: String): Boolean = runCatching {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}.isSuccess
