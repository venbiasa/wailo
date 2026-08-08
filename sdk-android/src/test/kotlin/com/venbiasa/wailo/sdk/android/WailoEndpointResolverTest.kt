package com.venbiasa.wailo.sdk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which desktop the SDK dials, and — the part that matters for ADR-0040 — which ones it refuses to dial
 * on its own. Discovery may only ever *re*-connect: reaching a new desktop is always a deliberate act,
 * because a colleague's Studio on the same WiFi advertises just as loudly.
 */
class WailoEndpointResolverTest {

    private val trusted = WailoDesktop(name = "mine", host = "10.0.0.2", port = 8899, studioId = "aaaa")
    private val stranger = WailoDesktop(name = "theirs", host = "10.0.0.3", port = 8899, studioId = "bbbb")

    @Before
    fun setUp() {
        WailoPairingStore.storage = InMemoryKeyValueStore()
        WailoHostStore.storage = InMemoryKeyValueStore()
    }

    @Test
    fun nothingConfiguredFallsBackToLoopback() {
        val endpoint = WailoEndpointResolver.resolve(null, null, emptyList())
        assertEquals("localhost", endpoint.host)
        assertEquals(WailoClient.DEFAULT_PORT, endpoint.port)
        assertTrue(endpoint.isLoopback())
    }

    @Test
    fun anExplicitHostOutranksEverything() {
        WailoHostStore.host = "10.0.0.9"
        remember(trusted)
        val endpoint = WailoEndpointResolver.resolve("192.168.1.5:1234", null, listOf(trusted))
        assertEquals("192.168.1.5", endpoint.host)
        assertEquals(1234, endpoint.port)
    }

    @Test
    fun aPinnedHostOutranksDiscovery() {
        remember(trusted)
        WailoHostStore.host = "10.0.0.9"
        WailoHostStore.port = 9100
        val endpoint = WailoEndpointResolver.resolve(null, null, listOf(trusted))
        assertEquals("10.0.0.9", endpoint.host)
        assertEquals(9100, endpoint.port)
    }

    @Test
    fun aTrustedDiscoveredDesktopIsDialled() {
        remember(trusted)
        val endpoint = WailoEndpointResolver.resolve(null, null, listOf(stranger, trusted))
        assertEquals("10.0.0.2", endpoint.host)
        assertEquals("aaaa", endpoint.studioId)
    }

    /** The original ADR-0040 fix: a colleague's Studio used to win by sorting first. */
    @Test
    fun anUnknownDiscoveredDesktopIsIgnored() {
        val endpoint = WailoEndpointResolver.resolve(null, null, listOf(stranger))
        assertEquals("localhost", endpoint.host)
        assertNull(WailoEndpointResolver.firstTrusted(listOf(stranger)))
    }

    /** A latched refusal has to stop the 2-second loop walking back into the same "no". */
    @Test
    fun aRefusedDesktopIsNotDialled() {
        remember(trusted, refused = true)
        assertNull(WailoEndpointResolver.firstTrusted(listOf(trusted)))
        assertEquals("localhost", WailoEndpointResolver.resolve(null, null, listOf(trusted)).host)
    }

    @Test
    fun aTypedAddressResolvesBackToTheStudioLastReachedThere() {
        remember(trusted)
        val pairing = WailoEndpointResolver.pairingFor("10.0.0.2", studioId = null)
        assertEquals("aaaa", pairing?.studioId)
    }

    /** One developer with one Mac: a desktop that moved networks must not pair a second time. */
    @Test
    fun theOnlyPairingThereIsCoversAnAddressItHasNeverAnsweredAt() {
        remember(trusted)
        assertEquals("aaaa", WailoEndpointResolver.pairingFor("10.0.0.77", null)?.studioId)
    }

    /** With two, guessing would be worse than asking, so an unfamiliar address is a stranger. */
    @Test
    fun anUnfamiliarAddressWithSeveralPairingsResolvesToNone() {
        remember(trusted)
        remember(stranger)
        assertNull(WailoEndpointResolver.pairingFor("10.0.0.77", null))
    }

    @Test
    fun loopbackIsRecognisedByResolutionRatherThanSpelling() {
        assertTrue(WailoEndpoint("127.0.0.1", 8899, null).isLoopback())
        assertTrue(WailoEndpoint("localhost", 8899, null).isLoopback())
        assertTrue(WailoEndpoint("[::1]", 8899, null).isLoopback())
        assertFalse(WailoEndpoint("10.0.0.2", 8899, null).isLoopback())
    }

    private fun remember(desktop: WailoDesktop, refused: Boolean = false) {
        WailoPairingStore.save(
            WailoPairing(
                studioId = desktop.studioId,
                deviceKey = ByteArray(32) { 1 },
                publicKey = ByteArray(65) { 2 },
                sessionCounter = 1,
                refused = refused,
                lastHost = desktop.host,
                trustedOnFirstUse = false,
            ),
        )
    }
}
