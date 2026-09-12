package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostMapLocalRule
import com.venbiasa.wailo.host.HostScript
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.proxy.RequestVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HostProxyScriptPipelineTest {
    @Test
    fun `request scripts feed map local and response scripts transform its fixture`() = runBlocking {
        val host = HeadlessHost.wrap(WailoEngine())
        val executor = DaemonScriptExecutor()
        host.installScriptExecutor(executor)
        try {
            val source = """
                function onRequest({ request }) {
                  request.url = "https://example.com/mapped";
                  return request;
                }
                function onResponse({ response }) {
                  response.body.answer += 1;
                  return response;
                }
            """.trimIndent()
            val hooks = host.validateScript(source)
            host.replaceScripts(
                listOf(
                    HostScript(
                        id = "script",
                        urlPattern = "https://example.com/*",
                        source = source,
                        onRequest = hooks.onRequest,
                        onResponse = hooks.onResponse,
                    ),
                ),
                enabled = true,
            )
            host.replaceMapLocalRules(
                listOf(
                    HostMapLocalRule(
                        id = "mapped",
                        urlPattern = "https://example.com/mapped",
                        headers = listOf(Header("Content-Type", "application/json")),
                        body = """{"answer":1}""".toByteArray(),
                    ),
                ),
                enabled = true,
            )

            val rules = HostProxyRules(host)
            val interception = rules.intercepts("GET", "https://example.com/original")
            assertTrue(interception.transformRequest)
            assertTrue(interception.transformResponse)

            val verdict = rules.onRequest(
                "test",
                HttpRequest(method = "GET", url = "https://example.com/original"),
            ) as RequestVerdict.Respond
            assertEquals("https://example.com/mapped", verdict.editedRequest?.url)
            assertEquals("""{"answer":2}""", verdict.response.body.utf8())
        } finally {
            host.stop()
        }
    }
}
