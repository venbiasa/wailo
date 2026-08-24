package com.venbiasa.wailo.host

import com.venbiasa.wailo.protocol.BreakpointRule

/** A breakpoint definition retained by a headless frontend and compiled to wire match metadata. */
class HostBreakpointRule(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String,
    /** One method, or blank for any — the same scalar contract Map Local carries (ADR-0087). */
    val method: String = "",
    val onRequest: Boolean = false,
    val onResponse: Boolean = true,
) {
    internal fun toProtocolRule() = BreakpointRule(
        id = id,
        enabled = enabled,
        url_pattern = urlPattern,
        methods = listOfNotNull(method.takeIf { it.isNotBlank() }),
        on_request = onRequest,
        on_response = onResponse,
    )
}
