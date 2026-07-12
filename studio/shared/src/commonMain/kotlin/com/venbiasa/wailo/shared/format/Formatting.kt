package com.venbiasa.wailo.shared.format

import kotlin.math.round

/** How an HTTP outcome should be categorized for coloring and labeling in the list. */
internal enum class StatusKind { Success, Redirect, ClientError, ServerError, Failed, Pending }

internal fun statusKind(code: Int?, hasError: Boolean): StatusKind = when {
    hasError -> StatusKind.Failed
    code == null || code <= 0 -> StatusKind.Pending
    code in 200..299 -> StatusKind.Success
    code in 300..399 -> StatusKind.Redirect
    code in 400..499 -> StatusKind.ClientError
    code in 500..599 -> StatusKind.ServerError
    else -> StatusKind.Pending
}

/** Short badge for the status column: the code, `ERR` for a transport failure, `—` while pending. */
internal fun statusLabel(code: Int?, hasError: Boolean): String = when {
    hasError -> "ERR"
    code == null || code <= 0 -> "—"
    else -> code.toString()
}

/** Word label for the traffic list's Status column (the colored, at-a-glance outcome). */
internal fun statusText(kind: StatusKind): String = when (kind) {
    StatusKind.Success -> "Success"
    StatusKind.Redirect -> "Redirect"
    StatusKind.ClientError -> "Client Error"
    StatusKind.ServerError -> "Server Error"
    StatusKind.Failed -> "Failed"
    StatusKind.Pending -> "Pending"
}

/** Raw HTTP code for the Code column; `—` when there is no response code yet (or a transport failure). */
internal fun codeText(code: Int?): String =
    if (code == null || code <= 0) "—" else code.toString()

/** HTTP verb families, used only to pick a method color. */
internal enum class MethodKind { Read, Create, Update, Delete, Other }

internal fun methodKind(method: String): MethodKind = when (method.trim().uppercase()) {
    "GET", "HEAD", "OPTIONS" -> MethodKind.Read
    "POST" -> MethodKind.Create
    "PUT", "PATCH" -> MethodKind.Update
    "DELETE" -> MethodKind.Delete
    else -> MethodKind.Other
}

internal data class UrlParts(val host: String, val pathAndQuery: String)

/** Splits a URL into host and path+query for the two-tone list row, tolerant of malformed input. */
internal fun splitUrl(url: String): UrlParts {
    val schemeIdx = url.indexOf("://")
    if (schemeIdx < 0) return UrlParts(host = "", pathAndQuery = url)
    val afterScheme = schemeIdx + 3
    val slash = url.indexOf('/', afterScheme)
    return if (slash < 0) {
        UrlParts(host = url.substring(afterScheme), pathAndQuery = "/")
    } else {
        UrlParts(host = url.substring(afterScheme, slash), pathAndQuery = url.substring(slash))
    }
}

/** Human-readable byte size (`—` for empty/unknown, `340 B`, `1.2 KB`, `3.4 MB`, …). */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val scaled = round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10} ${units[unit]}"
}

/**
 * Wall-clock `HH:mm:ss.SSS` for a *local* epoch-milliseconds value (caller adds the zone offset).
 * Pure so it works in commonMain; `java.time` is unavailable here.
 */
internal fun formatClockTime(epochMsLocal: Long): String {
    val dayMs = 86_400_000L
    val msOfDay = ((epochMsLocal % dayMs) + dayMs) % dayMs
    val ms = msOfDay % 1000
    val totalSeconds = msOfDay / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = (totalSeconds / 3600) % 24
    return "${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}.${pad3(ms)}"
}

private fun pad2(value: Long): String = if (value < 10) "0$value" else "$value"

private fun pad3(value: Long): String = when {
    value < 10 -> "00$value"
    value < 100 -> "0$value"
    else -> "$value"
}
