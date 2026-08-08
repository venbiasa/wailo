package com.venbiasa.wailo.sdk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Address parsing, mirroring `sdk-ios/Tests/WailoSDKTests/WailoAddressTests.swift`.
 *
 * The doubled-port case is the reason this type exists: typing `10.0.0.2:8080` into a field labelled
 * "address" produced `ws://10.0.0.2:8080:8899/` on iOS and crashed the host app on every launch, because
 * the text was stored before it was ever dialled (ADR-0035).
 */
class WailoAddressTest {

    @Test
    fun aBareHostLeavesThePortToTheCaller() {
        val address = WailoAddress.parse("10.0.0.2")
        assertEquals("10.0.0.2", address?.host)
        assertNull(address?.port)
    }

    @Test
    fun aHostAndPortSplitApart() {
        val address = WailoAddress.parse("10.0.0.2:9000")
        assertEquals("10.0.0.2", address?.host)
        assertEquals(9000, address?.port)
    }

    @Test
    fun aPastedUrlIsReducedToItsAuthority() {
        assertEquals("10.0.0.2:9000", WailoAddress.parse("ws://10.0.0.2:9000/")?.toString())
        assertEquals("studio.local", WailoAddress.parse("  http://studio.local  ")?.toString())
    }

    @Test
    fun aBareIpv6LiteralIsBracketed() {
        assertEquals("[fe80::1]", WailoAddress.parse("fe80::1")?.toString())
        assertEquals("[fe80::1]:9000", WailoAddress.parse("[fe80::1]:9000")?.toString())
    }

    /** The crash from ADR-0035: several colons that are not a v6 literal name nothing diallable. */
    @Test
    fun aDoubledPortIsRefused() {
        assertNull(WailoAddress.parse("10.0.0.2:8080:9000"))
    }

    @Test
    fun anOutOfRangePortIsRefused() {
        assertNull(WailoAddress.parse("10.0.0.2:0"))
        assertNull(WailoAddress.parse("10.0.0.2:65536"))
        assertNull(WailoAddress.parse("10.0.0.2:http"))
    }

    @Test
    fun textThatNamesNoHostIsRefused() {
        assertNull(WailoAddress.parse(""))
        assertNull(WailoAddress.parse("   "))
        assertNull(WailoAddress.parse("10.0.0.2/path"))
        assertNull(WailoAddress.parse("user@10.0.0.2"))
        assertNull(WailoAddress.parse("[not-a-v6]:9000"))
    }

    @Test
    fun everyParsedAddressReparsesToItself() {
        for (text in listOf("localhost", "10.0.0.2:9000", "[fe80::1]:8899", "studio.local")) {
            val once = WailoAddress.parse(text)
            assertEquals(once, WailoAddress.parse(once.toString()))
        }
    }
}
