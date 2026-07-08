package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.protocol.HttpExchange

/** Where captured exchanges are delivered; keeps interceptors from hard-coding a transport. */
fun interface CaptureSink {
    fun onExchange(exchange: HttpExchange)
}

/** Fan-out to several sinks, e.g. stream to the desktop while also logging locally. */
operator fun CaptureSink.plus(other: CaptureSink): CaptureSink {
    val first = this
    return CaptureSink { exchange ->
        first.onExchange(exchange)
        other.onExchange(exchange)
    }
}
