package com.venbiasa.wailo.desktop

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.shared.ResponseHeader
import java.io.File

/**
 * Where the host keeps files it manages on the user's behalf: the OS's per-user app-data directory.
 * Authored response bodies (Map Local's and Seed's) live here rather than in prefs, which is for small
 * values — so a matched request reads its body fresh from disk (ADR-0019) and prefs never holds bytes.
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

/**
 * Every managed body file for a rule id under [dir] (there should be at most one). A prefix scan rather
 * than a fixed name so it finds the body whatever its extension, sweeps a stale file left by an
 * interrupted type switch, and still finds the legacy fixed-name ".json" body written before bodies were
 * typed. The trailing dot makes "$id." delimit the id, so sibling ids that share a prefix never match.
 */
internal fun managedBodyFiles(dir: File, id: String): List<File> =
    dir.listFiles { file -> file.name.startsWith("$id.") }?.toList() ?: emptyList()

/**
 * The response headers to serve alongside [bytes] read from [file]: the rule's [authored] headers pass
 * through as-is, except Content-Length (the host owns it, recomputed from the bytes so it can't drift and
 * truncate or hang the response); Content-Type falls back to an extension guess when the rule set none.
 * Shared by Map Local's served bodies and Seed's breakpoint responses — both answer with an authored
 * rule's response, so both owe the client the same corrections.
 */
internal fun servedHeaders(authored: List<ResponseHeader>, file: File, bytes: ByteArray): List<Header> {
    val named = authored.filter { it.name.isNotBlank() }
    return buildList {
        named.forEach { header ->
            if (!header.name.equals("Content-Length", ignoreCase = true)) {
                add(Header(name = header.name, value_ = header.value))
            }
        }
        if (named.none { it.name.equals("Content-Type", ignoreCase = true) }) {
            val guessed = guessContentType(file.name)
            if (guessed.isNotBlank()) add(Header(name = "Content-Type", value_ = guessed))
        }
        add(Header(name = "Content-Length", value_ = bytes.size.toString()))
    }
}
