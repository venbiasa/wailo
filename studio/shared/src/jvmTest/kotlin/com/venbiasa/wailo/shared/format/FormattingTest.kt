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
    fun statusChipTextCombinesCodeAndReason() {
        // Server message wins when present.
        assertEquals("201 Created", statusChipText(201, "Created", hasError = false))
        assertEquals("200 Yeah", statusChipText(200, "Yeah", hasError = false))
        // Falls back to the standard reason phrase when the SDK omits it (e.g. HTTP/2).
        assertEquals("200 OK", statusChipText(200, "", hasError = false))
        assertEquals("404 Not Found", statusChipText(404, "", hasError = false))
        // Unknown code with no message degrades to the bare number.
        assertEquals("299", statusChipText(299, "", hasError = false))
        // Transport failure and pending states read as words, matching the status color.
        assertEquals("Failed", statusChipText(500, "", hasError = true))
        assertEquals("Pending", statusChipText(null, "", hasError = false))
        assertEquals("Pending", statusChipText(0, "", hasError = false))
    }

    @Test
    fun basicAuthDecodesOnlyBasicAuthorization() {
        // "dXNlcjpwYXNz" is base64 for "user:pass".
        assertEquals("user:pass", basicAuthDecoded("Authorization", "Basic dXNlcjpwYXNz"))
        assertEquals("user:pass", basicAuthDecoded("authorization", "basic dXNlcjpwYXNz"))
        assertEquals(null, basicAuthDecoded("Authorization", "Bearer abc.def"))
        assertEquals(null, basicAuthDecoded("Cookie", "Basic dXNlcjpwYXNz"))
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
    fun urlSegmentsTagsEachPart() {
        val segments = urlSegments("https://api.example.com:8080/v1/users?id=42&sort=asc#top")
        assertEquals(
            listOf(
                UrlSegment("https", UrlPart.Scheme),
                UrlSegment("://", UrlPart.Separator),
                UrlSegment("api.example.com", UrlPart.Host),
                UrlSegment(":", UrlPart.Separator),
                UrlSegment("8080", UrlPart.Port),
                UrlSegment("/v1/users", UrlPart.Path),
                UrlSegment("?", UrlPart.Separator),
                UrlSegment("id=42&sort=asc", UrlPart.Query),
                UrlSegment("#", UrlPart.Separator),
                UrlSegment("top", UrlPart.Fragment),
            ),
            segments,
        )
    }

    @Test
    fun urlSegmentsReconstructTheInputExactly() {
        for (url in listOf(
            "https://example.com/ping?x=1",
            "http://h:8080/a/b",
            "https://example.com",
            "/relative/path?q=1#frag",
            "not a url",
            "",
        )) {
            assertEquals(url, urlSegments(url).joinToString("") { it.text })
        }
    }

    @Test
    fun urlSegmentsToleratesMissingParts() {
        // No port: the authority is all host.
        assertEquals(
            listOf(
                UrlSegment("https", UrlPart.Scheme),
                UrlSegment("://", UrlPart.Separator),
                UrlSegment("example.com", UrlPart.Host),
                UrlSegment("/p", UrlPart.Path),
            ),
            urlSegments("https://example.com/p"),
        )
        // No scheme/authority we can trust: the whole thing is the path.
        assertEquals(listOf(UrlSegment("not a url", UrlPart.Path)), urlSegments("not a url"))
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
