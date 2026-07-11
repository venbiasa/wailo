package com.venbiasa.wailo.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {

    @Test
    fun statusKindCategorizesByRangeAndError() {
        assertEquals(StatusKind.Success, statusKind(200, hasError = false))
        assertEquals(StatusKind.Success, statusKind(204, hasError = false))
        assertEquals(StatusKind.Redirect, statusKind(301, hasError = false))
        assertEquals(StatusKind.ClientError, statusKind(404, hasError = false))
        assertEquals(StatusKind.ServerError, statusKind(503, hasError = false))
        assertEquals(StatusKind.Pending, statusKind(null, hasError = false))
        assertEquals(StatusKind.Pending, statusKind(0, hasError = false))
        // A transport failure wins over any code.
        assertEquals(StatusKind.Failed, statusKind(null, hasError = true))
        assertEquals(StatusKind.Failed, statusKind(200, hasError = true))
    }

    @Test
    fun statusLabelPrefersErrorThenCode() {
        assertEquals("ERR", statusLabel(500, hasError = true))
        assertEquals("200", statusLabel(200, hasError = false))
        assertEquals("—", statusLabel(null, hasError = false))
        assertEquals("—", statusLabel(0, hasError = false))
    }

    @Test
    fun statusTextLabelsEachKind() {
        assertEquals("Success", statusText(StatusKind.Success))
        assertEquals("Redirect", statusText(StatusKind.Redirect))
        assertEquals("Client Error", statusText(StatusKind.ClientError))
        assertEquals("Server Error", statusText(StatusKind.ServerError))
        assertEquals("Failed", statusText(StatusKind.Failed))
        assertEquals("Pending", statusText(StatusKind.Pending))
    }

    @Test
    fun codeTextShowsCodeOrDash() {
        assertEquals("200", codeText(200))
        assertEquals("404", codeText(404))
        assertEquals("—", codeText(null))
        assertEquals("—", codeText(0))
        assertEquals("—", codeText(-1))
    }

    @Test
    fun methodKindMapsVerbs() {
        assertEquals(MethodKind.Read, methodKind("get"))
        assertEquals(MethodKind.Read, methodKind("HEAD"))
        assertEquals(MethodKind.Create, methodKind("POST"))
        assertEquals(MethodKind.Update, methodKind("PUT"))
        assertEquals(MethodKind.Update, methodKind("patch"))
        assertEquals(MethodKind.Delete, methodKind("DELETE"))
        assertEquals(MethodKind.Other, methodKind("PROPFIND"))
    }

    @Test
    fun splitUrlSeparatesHostFromPath() {
        assertEquals(UrlParts("example.com", "/ping?x=1"), splitUrl("https://example.com/ping?x=1"))
        assertEquals(UrlParts("example.com", "/"), splitUrl("https://example.com"))
        assertEquals(UrlParts("h:8080", "/a/b"), splitUrl("http://h:8080/a/b"))
        // Malformed input degrades to "no host, whole thing is the path".
        assertEquals(UrlParts("", "not a url"), splitUrl("not a url"))
    }

    @Test
    fun formatBytesIsHumanReadable() {
        assertEquals("—", formatBytes(0))
        assertEquals("—", formatBytes(-1))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun formatClockTimeRendersLocalMillis() {
        assertEquals("00:00:00.000", formatClockTime(0))
        assertEquals("00:00:01.000", formatClockTime(1_000))
        assertEquals("01:01:01.234", formatClockTime(3_661_234))
        // Wraps within a day and stays non-negative.
        assertEquals("00:00:00.000", formatClockTime(86_400_000))
    }
}
