package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostScript
import com.venbiasa.wailo.host.ScriptRuntimeIssueCode
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class DaemonScriptExecutorTest {
    @Test
    fun `validates hooks and transforms a json response`() = runBlocking {
        val executor = DaemonScriptExecutor()
        try {
            val source = """
                const freeze = "user binding";
                const requestHook = "user binding";
                function body() { return "user binding"; }
                function onResponse({ response }) {
                  response.statusCode = 201;
                  response.body.value += 1;
                  response.headers.append("X-Script", "yes");
                  response.delayMs = 12;
                  return response;
                }
            """.trimIndent()
            val validation = executor.validate(source)
            assertFalse(validation.onRequest)
            assertTrue(validation.onResponse)

            val result = executor.execute(
                listOf(script("json", source, onResponse = true)),
                ScriptTransformRequest(
                    correlation_id = "phase",
                    phase = ScriptPhase.SCRIPT_PHASE_RESPONSE,
                    request = HttpRequest(method = "GET", url = "https://example.com/items"),
                    response = HttpResponse(
                        code = 200,
                        headers = listOf(Header("Content-Type", "application/json")),
                        body = """{"value":1}""".encodeUtf8(),
                        body_size = 11,
                    ),
                    request_body_replayable = true,
                ),
            )

            assertEquals(201, result.response?.code)
            assertEquals("""{"value":2}""", result.response?.body?.utf8())
            assertEquals(listOf("yes"), result.response?.headers?.filter { it.name == "X-Script" }?.map { it.value_ })
            assertEquals(12, result.delay_ms)
            assertTrue(result.response_body_replaced)
            assertTrue(executor.issues.value.isEmpty())
        } finally {
            executor.close()
        }
    }

    @Test
    fun `invalid later script rolls back only that script`() = runBlocking {
        val executor = DaemonScriptExecutor()
        try {
            val first = """
                function onRequest({ request }) {
                  request.url = "https://example.com/committed";
                  return request;
                }
            """.trimIndent()
            val invalid = """
                function onRequest({ request }) {
                  request.method = "PATCH";
                  request.url = "/not-absolute";
                  return request;
                }
            """.trimIndent()
            executor.validate(first)
            executor.validate(invalid)
            val input = ScriptTransformRequest(
                correlation_id = "phase",
                phase = ScriptPhase.SCRIPT_PHASE_REQUEST,
                request = HttpRequest(method = "GET", url = "https://example.com/start"),
                request_body_replayable = true,
            )
            val result = executor.execute(
                listOf(
                    script("first", first, onRequest = true),
                    script("invalid", invalid, onRequest = true),
                ),
                input,
            )

            assertEquals("GET", result.request?.method)
            assertEquals("https://example.com/committed", result.request?.url)
            assertEquals(
                ScriptRuntimeIssueCode.INVALID_URL,
                executor.issues.value.single { it.ruleId == "invalid" }.code,
            )
            assertNull(executor.issues.value.firstOrNull { it.ruleId == "first" })
        } finally {
            executor.close()
        }
    }

    @Test
    fun `unavailable request body cannot be replaced and host access is denied`() = runBlocking {
        val executor = DaemonScriptExecutor()
        try {
            val unavailable = """
                function onRequest({ request }) {
                  request.url = "https://example.com/changed";
                  request.bodyType = "text";
                  request.body = "replacement";
                  return request;
                }
            """.trimIndent()
            val hostAccess = """
                function onRequest({ request }) {
                  Java.type("java.lang.System");
                  return request;
                }
            """.trimIndent()
            val hideReplayable = """
                function onRequest({ request }) {
                  request.bodyType = "unavailable";
                  request.body = undefined;
                  return request;
                }
            """.trimIndent()
            executor.validate(unavailable)
            executor.validate(hostAccess)
            executor.validate(hideReplayable)
            val input = ScriptTransformRequest(
                correlation_id = "phase",
                phase = ScriptPhase.SCRIPT_PHASE_REQUEST,
                request = HttpRequest(
                    method = "POST",
                    url = "https://example.com/start",
                    body_size = -1,
                    body_truncated = true,
                ),
                request_body_replayable = false,
            )

            val unavailableResult = executor.execute(
                listOf(script("body", unavailable, onRequest = true)),
                input,
            )
            assertEquals(input.request, unavailableResult.request)
            assertFalse(unavailableResult.request_body_replaced)
            assertEquals(ScriptRuntimeIssueCode.INVALID_BODY, executor.issues.value.single().code)

            val replayableInput = input.copy(
                request = requireNotNull(input.request).copy(
                    headers = listOf(Header("Content-Type", "text/plain")),
                    body = "original".encodeUtf8(),
                    body_size = 8,
                    body_truncated = false,
                ),
                request_body_replayable = true,
            )
            val hiddenResult = executor.execute(
                listOf(script("hide", hideReplayable, onRequest = true)),
                replayableInput,
            )
            assertEquals(replayableInput.request, hiddenResult.request)
            assertEquals(
                ScriptRuntimeIssueCode.INVALID_BODY,
                executor.issues.value.single { it.ruleId == "hide" }.code,
            )

            val hostResult = executor.execute(
                listOf(script("host", hostAccess, onRequest = true)),
                input,
            )
            assertEquals(input.request, hostResult.request)
            assertNotNull(executor.issues.value.firstOrNull { it.ruleId == "host" })
            Unit
        } finally {
            executor.close()
        }
    }

    @Test
    fun `response hook cannot mutate binary request context through typed array escape hatches`() = runBlocking {
        val executor = DaemonScriptExecutor()
        try {
            val source = """
                function onResponse({ request, response }) {
                  let blocked = 0;
                  const attempts = [
                    () => request.body.fill(0),
                    () => new Uint8Array(request.body.buffer).fill(0),
                    () => request.body.subarray().fill(0),
                    () => request.body.valueOf().fill(0),
                  ];
                  for (const attempt of attempts) {
                    try { attempt(); } catch (_) { blocked += 1; }
                  }
                  response.bodyType = "json";
                  response.body = { bytes: Array.from(request.body), blocked };
                  return response;
                }
            """.trimIndent()
            executor.validate(source)
            val result = executor.execute(
                listOf(script("readonly", source, onResponse = true)),
                ScriptTransformRequest(
                    correlation_id = "phase",
                    phase = ScriptPhase.SCRIPT_PHASE_RESPONSE,
                    request = HttpRequest(
                        method = "POST",
                        url = "https://example.com/items",
                        headers = listOf(Header("Content-Type", "application/octet-stream")),
                        body = byteArrayOf(1, 2, 3).toByteString(),
                        body_size = 3,
                    ),
                    response = HttpResponse(
                        code = 200,
                        headers = listOf(Header("Content-Type", "text/plain")),
                        body = "ok".encodeUtf8(),
                        body_size = 2,
                    ),
                    request_body_replayable = true,
                ),
            )

            assertEquals("""{"bytes":[1,2,3],"blocked":3}""", result.response?.body?.utf8())
            assertTrue(executor.issues.value.isEmpty())
        } finally {
            executor.close()
        }
    }

    @Test
    fun `response hook cannot mutate request headers through implementation fields`() = runBlocking {
        val executor = DaemonScriptExecutor()
        try {
            val source = """
                function onResponse({ request, response }) {
                  let blocked = 0;
                  const attempts = [
                    () => request.headers.set("X-Input", "changed"),
                    () => { request.headers._mutable = true; },
                    () => request.headers._pairs.push(["X-Input", "changed"]),
                    () => Object.setPrototypeOf(request.headers, {}),
                  ];
                  for (const attempt of attempts) {
                    try { attempt(); } catch (_) { blocked += 1; }
                  }
                  response.bodyType = "json";
                  response.body = { header: request.headers.get("X-Input"), blocked };
                  return response;
                }
            """.trimIndent()
            executor.validate(source)
            val result = executor.execute(
                listOf(script("readonly", source, onResponse = true)),
                ScriptTransformRequest(
                    correlation_id = "phase",
                    phase = ScriptPhase.SCRIPT_PHASE_RESPONSE,
                    request = HttpRequest(
                        method = "GET",
                        url = "https://example.com/items",
                        headers = listOf(Header("X-Input", "original")),
                    ),
                    response = HttpResponse(
                        code = 200,
                        headers = listOf(Header("Content-Type", "text/plain")),
                        body = "ok".encodeUtf8(),
                        body_size = 2,
                    ),
                    request_body_replayable = true,
                ),
            )

            assertEquals("""{"header":"original","blocked":4}""", result.response?.body?.utf8())
            assertTrue(executor.issues.value.isEmpty())
        } finally {
            executor.close()
        }
    }

    private fun script(
        id: String,
        source: String,
        onRequest: Boolean = false,
        onResponse: Boolean = false,
    ) = HostScript(
        id = id,
        urlPattern = "*",
        source = source,
        onRequest = onRequest,
        onResponse = onResponse,
    )
}
