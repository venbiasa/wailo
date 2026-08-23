package com.venbiasa.wailo.desktop

import java.io.File

/**
 * Where the host keeps files it manages on the user's behalf: the OS's per-user app-data directory.
 * Authored bodies used to live here; they are the daemon's now (ADR-0085), so what remains is the
 * directory itself and the launch sweep that clears what it left behind.
 */
internal fun appDataDir(): File {
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
 * The file extension to store a body under, from its Content-Type — the inverse of [guessContentType],
 * used to name a managed body file so the stored body is self-describing (a .png holds an image, .json
 * holds JSON) and the extension guess still works if the header is later cleared. Unknown/absent types
 * fall back to a neutral ".bin".
 */
internal fun extensionForContentType(contentType: String?): String =
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

/** The Content-Type a file's extension implies, for a served body whose rule authored no Content-Type. */
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

