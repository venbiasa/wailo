package com.venbiasa.wailo.host

import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import okio.ByteString.Companion.toByteString

/**
 * Applies a frontend's edits to a held request on its way back out (ADR-0067). A null argument means
 * "leave this as the device sent it", never "clear it", so a caller rewriting one field cannot blank the
 * rest by omission.
 *
 * It lives here because the CLI and MCP both resume holds and have to agree on what an edit means —
 * including the size and truncation bookkeeping, which a hand-written second copy gets wrong quietly.
 * What they do not share is where the headers come from: MCP has to put back the values its own
 * redaction replaced before they can be sent, so it passes the restored list in.
 */
fun HttpRequest.withEdits(
    method: String? = null,
    url: String? = null,
    headers: List<Header>? = null,
    body: ByteArray? = null,
): HttpRequest = HttpRequest(
    method = method ?: this.method,
    url = url ?: this.url,
    headers = headers ?: this.headers,
    body = body?.toByteString() ?: this.body,
    body_size = body?.size?.toLong() ?: body_size,
    // A replaced body is whole by construction, whatever the captured one was.
    body_truncated = if (body == null) body_truncated else false,
)

/** The response-phase counterpart of [withEdits]. `message` stays as sent: the code is what a caller edits. */
fun HttpResponse.withEdits(
    statusCode: Int? = null,
    headers: List<Header>? = null,
    body: ByteArray? = null,
): HttpResponse = HttpResponse(
    code = statusCode ?: code,
    message = message,
    headers = headers ?: this.headers,
    body = body?.toByteString() ?: this.body,
    body_size = body?.size?.toLong() ?: body_size,
    body_truncated = if (body == null) body_truncated else false,
)
