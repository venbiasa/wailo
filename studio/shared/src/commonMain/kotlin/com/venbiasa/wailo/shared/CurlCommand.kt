package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.HttpRequest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Builds a shell-safe cURL command for the captured request, including every captured header and body
 * byte. Text bodies stay readable; arbitrary binary bodies are piped through `printf` as octal escapes.
 */
internal fun HttpRequest.toCurlCommand(): String {
    val textBody = body.utf8().takeIf { '\u0000' !in it && it.encodeUtf8() == body }
    val command = buildString {
        append("curl ")
        append(shellQuote(url))
        if (method.isNotBlank()) {
            append(" \\\n  -X ")
            append(shellQuote(method))
        }
        headers.forEach { header ->
            append(" \\\n  -H ")
            append(shellQuote("${header.name}: ${header.value_}"))
        }
        if (body.size > 0) {
            append(" \\\n  ")
            if (textBody != null) {
                append(if (textBody.startsWith("@")) "--data-raw " else "-d ")
                append(shellQuote(textBody))
            } else {
                append("--data-binary @-")
            }
        }
    }
    return if (body.size > 0 && textBody == null) {
        "printf '%b' '${body.toPrintfOctal()}' | $command"
    } else {
        command
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun ByteString.toPrintfOctal(): String = buildString(size * 5) {
    toByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        append("\\0")
        append(('0'.code + (value shr 6)).toChar())
        append(('0'.code + ((value shr 3) and 7)).toChar())
        append(('0'.code + (value and 7)).toChar())
    }
}
