package com.venbiasa.wailo.host

import com.venbiasa.wailo.protocol.BreakpointRule

/** A breakpoint definition retained by a headless frontend and compiled to wire match metadata. */
class HostBreakpointRule(
    val id: String,
    val enabled: Boolean = true,
    val urlPattern: String,
    methods: List<String> = emptyList(),
    val onRequest: Boolean = false,
    val onResponse: Boolean = true,
) {
    val methods: List<String> = methods.toList()

    internal fun toProtocolRule() = BreakpointRule(
        id = id,
        enabled = enabled,
        url_pattern = urlPattern,
        methods = methods,
        on_request = onRequest,
        on_response = onResponse,
    )
}
