package com.venbiasa.wailo.desktop

/**
 * One comparison Studio has open, as the ids of the two rows it diffs. Studio holds a list of these and
 * gives each its own window, so a second comparison never displaces the first (ADR-0091).
 *
 * [key] is deliberately order-independent: A against B and B against A are the same question asked from
 * the other side, so they must resolve to one window — which is also what lets [withSidesSwapped] flip a
 * comparison without the window it lives in being torn down and rebuilt.
 */
internal data class Comparison(val left: String, val right: String) {
    val key: String = if (left <= right) "$left|$right" else "$right|$left"
}

/** Adds [next] unless that comparison is already open, in which case its window is raised instead. */
internal fun List<Comparison>.withOpened(next: Comparison): List<Comparison> =
    if (any { it.key == next.key }) this else this + next

internal fun List<Comparison>.withClosed(key: String): List<Comparison> = filterNot { it.key == key }

/** Flips the sides of one comparison, in place: its position in the list — and so its window — is kept. */
internal fun List<Comparison>.withSidesSwapped(key: String): List<Comparison> =
    map { if (it.key == key) Comparison(it.right, it.left) else it }

/**
 * Drops comparisons whose rows are no longer captured, so clearing the capture closes their windows rather
 * than leaving them on rows that no longer exist. Returns this same list when nothing was dropped: the live
 * ids are recomputed on every poll, and a fresh list each time would restate the state, and so the windows.
 */
internal fun List<Comparison>.withOnlyLive(liveIds: Set<String>): List<Comparison> {
    fun live(c: Comparison) = c.left in liveIds && c.right in liveIds
    return if (all(::live)) this else filter(::live)
}
