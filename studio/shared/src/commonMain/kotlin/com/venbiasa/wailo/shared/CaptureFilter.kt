package com.venbiasa.wailo.shared

/**
 * The capture filter the desktop authors and pushes to devices (ADR-0029): two host-pattern lists, each
 * with its own on/off switch, that decide which traffic a device captures and streams. When [allowEnabled]
 * only hosts matching [allowHosts] are captured; when [blockEnabled] hosts matching [blockHosts] are never
 * captured; with both on a host must match the allowlist and not the blocklist; with both off (the default)
 * everything is captured. The gate is whole-exchange — a filtered request is dropped at the source, never
 * captured as metadata-only.
 *
 * A list can be enabled only while it has entries: an empty list forces its switch off, so [allowEnabled]/
 * [blockEnabled] are always false when their list is empty (the mutation helpers keep this invariant, and
 * the host pushes/persists this coerced form). Host-owned and persisted (like the Map Local layout);
 * `shared` renders it and hands back a new value for every change, staying stateless over its inputs
 * (ADR-0013).
 */
data class CaptureFilterState(
    val allowEnabled: Boolean = false,
    val allowHosts: List<String> = emptyList(),
    val blockEnabled: Boolean = false,
    val blockHosts: List<String> = emptyList(),
) {
    /** Add [host] to the allowlist (deduped, order-preserving); a no-op for a blank or already-present host. */
    fun addAllow(host: String): CaptureFilterState =
        if (host.isBlank() || host in allowHosts) this else copy(allowHosts = allowHosts + host)

    /** Remove [host] from the allowlist; if that empties the list, its switch is forced off. */
    fun removeAllow(host: String): CaptureFilterState =
        copy(allowHosts = allowHosts - host).coerced()

    /** Add [host] to the blocklist (deduped, order-preserving); a no-op for a blank or already-present host. */
    fun addBlock(host: String): CaptureFilterState =
        if (host.isBlank() || host in blockHosts) this else copy(blockHosts = blockHosts + host)

    /** Remove [host] from the blocklist; if that empties the list, its switch is forced off. */
    fun removeBlock(host: String): CaptureFilterState =
        copy(blockHosts = blockHosts - host).coerced()

    /** Turn the allowlist on/off. Ignored while it has no entries (an empty list can't be enabled). */
    fun setAllowEnabled(enabled: Boolean): CaptureFilterState =
        copy(allowEnabled = enabled && allowHosts.isNotEmpty())

    /** Turn the blocklist on/off. Ignored while it has no entries (an empty list can't be enabled). */
    fun setBlockEnabled(enabled: Boolean): CaptureFilterState =
        copy(blockEnabled = enabled && blockHosts.isNotEmpty())

    // Re-assert the "empty list ⇒ switch off" invariant after a removal.
    private fun coerced(): CaptureFilterState = copy(
        allowEnabled = allowEnabled && allowHosts.isNotEmpty(),
        blockEnabled = blockEnabled && blockHosts.isNotEmpty(),
    )
}
