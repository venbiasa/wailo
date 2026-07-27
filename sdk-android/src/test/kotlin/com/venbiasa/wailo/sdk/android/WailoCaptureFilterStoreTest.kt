package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.CaptureFilter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture filter gate: allowlist-only, blocklist-only, both, enabled-empty, reset (ADR-0029). */
class WailoCaptureFilterStoreTest {

    @After
    fun tearDown() = WailoCaptureFilterStore.reset()

    @Test
    fun bothOffCapturesEverything() {
        WailoCaptureFilterStore.reset()
        assertTrue(WailoCaptureFilterStore.shouldCapture("anything.com"))
        assertTrue(WailoCaptureFilterStore.shouldCapture(null))
    }

    @Test
    fun allowlistOnlyCapturesMatches() {
        WailoCaptureFilterStore.replace(
            CaptureFilter(allowlist_enabled = true, allow_patterns = listOf("api.example.com", "*.other.com")),
        )
        assertTrue(WailoCaptureFilterStore.shouldCapture("api.example.com"))
        assertTrue(WailoCaptureFilterStore.shouldCapture("cdn.other.com"))
        assertFalse(WailoCaptureFilterStore.shouldCapture("evil.com"))
        // A bare host does not match a `*.` subdomain wildcard.
        assertFalse(WailoCaptureFilterStore.shouldCapture("other.com"))
    }

    @Test
    fun blocklistOnlyRejectsMatches() {
        WailoCaptureFilterStore.replace(
            CaptureFilter(blocklist_enabled = true, block_patterns = listOf("ads.example.com")),
        )
        assertFalse(WailoCaptureFilterStore.shouldCapture("ads.example.com"))
        assertTrue(WailoCaptureFilterStore.shouldCapture("api.example.com"))
    }

    @Test
    fun bothOnRequiresAllowedAndNotBlocked() {
        WailoCaptureFilterStore.replace(
            CaptureFilter(
                allowlist_enabled = true,
                allow_patterns = listOf("*.example.com"),
                blocklist_enabled = true,
                block_patterns = listOf("ads.example.com"),
            ),
        )
        assertTrue(WailoCaptureFilterStore.shouldCapture("api.example.com"))
        assertFalse(WailoCaptureFilterStore.shouldCapture("ads.example.com"))
        assertFalse(WailoCaptureFilterStore.shouldCapture("api.other.com"))
    }

    @Test
    fun enabledButEmptyListsAreInert() {
        // Enabled-empty allowlist matches nothing (captures nothing); enabled-empty blocklist blocks nothing.
        WailoCaptureFilterStore.replace(CaptureFilter(allowlist_enabled = true))
        assertFalse(WailoCaptureFilterStore.shouldCapture("api.example.com"))
        WailoCaptureFilterStore.replace(CaptureFilter(blocklist_enabled = true))
        assertTrue(WailoCaptureFilterStore.shouldCapture("api.example.com"))
    }

    @Test
    fun resetFallsBackToCaptureEverything() {
        WailoCaptureFilterStore.replace(CaptureFilter(allowlist_enabled = true, allow_patterns = listOf("only.com")))
        assertFalse(WailoCaptureFilterStore.shouldCapture("api.example.com"))
        WailoCaptureFilterStore.reset()
        assertTrue(WailoCaptureFilterStore.shouldCapture("api.example.com"))
    }
}
