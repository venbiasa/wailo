package com.venbiasa.wailo.sdk.android

/**
 * The wildcard/method matching shared by the three device-side stores (Map Local, breakpoints, capture
 * filter). Kept in one place so the device and desktop agree on exactly one scheme: `*` matches any run
 * of characters, everything else is literal, and the whole string must match. The iOS SDK duplicates
 * this per store (`WailoRuleStore`/`WailoBreakpointStore`/`WailoCaptureFilterStore`); consistency there
 * is by convention, here by a single seam.
 */

/** Method filter: an empty list means "any method"; otherwise a case-insensitive membership test. */
internal fun methodMatches(methods: List<String>, method: String): Boolean =
    methods.isEmpty() || methods.any { it.equals(method, ignoreCase = true) }

/** Whole-URL wildcard match, case-sensitive (Map Local / breakpoints match the full request URL). */
internal fun urlWildcardMatches(pattern: String, url: String): Boolean =
    wildcardRegex(pattern, ignoreCase = false).matches(url)

/**
 * Whole-host wildcard match, case-insensitive (the capture filter matches the request host). An empty
 * pattern never matches, so an enabled-but-empty allow/block list is inert rather than matching "".
 */
internal fun hostWildcardMatches(pattern: String, host: String): Boolean {
    if (pattern.isEmpty()) return false
    return wildcardRegex(pattern, ignoreCase = true).matches(host)
}

// Escape each literal segment between `*`s and rejoin with `.*`. `Regex.matches` anchors to the whole
// input, so this is equivalent to iOS's `^…$` over an escaped-then-`*`-restored pattern.
private fun wildcardRegex(pattern: String, ignoreCase: Boolean): Regex {
    val body = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    return Regex(body, options)
}
