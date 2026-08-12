package com.venbiasa.wailo.host

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
