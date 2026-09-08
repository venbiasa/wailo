package com.venbiasa.wailo.shared

import com.venbiasa.wailo.protocol.HttpExchange
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyPresentationTest {

    @Test
    fun onlyASuccessfulProxyConnectIsALockedTunnel() {
        assertTrue(entry(viaProxy = true, method = "CONNECT", code = 200).isLockedProxyTunnel())
        assertTrue(entry(viaProxy = true, method = "connect", code = 204).isLockedProxyTunnel())
        assertFalse(entry(viaProxy = false, method = "CONNECT", code = 200).isLockedProxyTunnel())
        assertFalse(entry(viaProxy = true, method = "GET", code = 200).isLockedProxyTunnel())
        assertFalse(entry(viaProxy = true, method = "CONNECT", code = 502).isLockedProxyTunnel())
        assertFalse(entry(viaProxy = true, method = "CONNECT", code = null).isLockedProxyTunnel())
    }

    @Test
    fun exactHostToggleNeverRemovesABroaderWildcard() {
        val wildcard = listOf("*.example.com")
        val added = wildcard.toggleExactHost("api.example.com")
        assertEquals(listOf("*.example.com", "api.example.com"), added)
        assertEquals(wildcard, added.toggleExactHost("api.example.com"))
    }

    private fun entry(
        viaProxy: Boolean,
        method: String,
        code: Int?,
    ) = FlowEntry(
        deviceName = "Proxy",
        appId = "browser",
        platform = "proxy",
        exchange = HttpExchange(
            id = "$viaProxy-$method-$code",
            request = HttpRequest(method = method, url = "https://api.example.com:443"),
            response = code?.let { HttpResponse(code = it) },
        ),
        viaProxy = viaProxy,
    )
}
