package com.venbiasa.wailo.host

import com.venbiasa.wailo.protocol.CaptureFilter

/**
 * Whole-URL wildcard match, case-sensitive: `*` matches any run of characters, everything else is
 * literal. Kept byte-identical to the device matchers (`sdk-android` `WailoMatching`, `sdk-ios`) and
 * to `shared`'s `seedUrlMatches` (ADR-0041) — Seed spends on the host, Map Local / breakpoints match
 * on the device, and a pattern copied between them must not diverge.
 */
fun urlPatternMatches(pattern: String, url: String): Boolean {
    if (pattern.isEmpty()) return false
    return Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(url)
}

/** Method filter: a blank pattern means "any method"; otherwise a case-insensitive comparison. */
fun methodPatternMatches(pattern: String, method: String): Boolean =
    pattern.isBlank() || pattern.equals(method, ignoreCase = true)

/**
 * Host wildcard match, case-insensitive — the Capture Filter's matcher rather than the whole-URL one
 * above (ADR-0029). Kept identical to `sdk-android`'s `hostWildcardMatches` and its iOS twin, because
 * the same filter is now applied in two places: a device gates its own capture, and the daemon gates
 * the proxy's, where there is no device to do it.
 */
fun hostWildcardMatches(pattern: String, host: String): Boolean {
    if (pattern.isEmpty()) return false
    return Regex(
        pattern.split("*").joinToString(".*") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    ).matches(host)
}

/** Whether [filter] admits [host], with the device's exact allow/block precedence. */
fun captureFilterAdmits(filter: CaptureFilter, host: String): Boolean {
    if (filter.allowlist_enabled && filter.allow_patterns.none { hostWildcardMatches(it, host) }) return false
    if (filter.blocklist_enabled && filter.block_patterns.any { hostWildcardMatches(it, host) }) return false
    return true
}
