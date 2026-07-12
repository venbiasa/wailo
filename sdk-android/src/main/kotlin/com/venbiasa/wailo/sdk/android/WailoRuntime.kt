package com.venbiasa.wailo.sdk.android

import okhttp3.OkHttpClient

/**
 * Runtime target for bytecode woven by the Wailo Gradle plugin. The plugin rewrites every
 * `OkHttpClient.Builder.build()` call site to `WailoRuntime.hook(builder).build()`, so clients built by
 * app code *and* third-party libraries are captured with no per-client wiring.
 *
 * Because auto-instrumentation owns no construction site to hand a sink to, the sink is process-global.
 * [WailoStartupProvider] installs a default one automatically in debuggable builds, so hosts normally
 * need no startup code; [install] is only for customising the sink (host/port, extra sinks).
 *
 * Kept intentionally tiny so invariant #3 holds — the heavy logic lives in the build-time plugin, not
 * in the SDK that ships inside the host app.
 */
object WailoRuntime {

    @Volatile
    private var sink: CaptureSink? = null

    @Volatile
    private var maxBodyBytes: Long = Wailo.DEFAULT_MAX_BODY_BYTES

    /**
     * Sets the sink that auto-instrumented clients report to. Usually unnecessary — [WailoStartupProvider]
     * installs a default WebSocket sink in debuggable builds. Call this only to customise (host/port,
     * extra sinks); it replaces and closes any previously installed [AutoCloseable] sink.
     */
    @JvmStatic
    @JvmOverloads
    fun install(sink: CaptureSink, maxBodyBytes: Long = Wailo.DEFAULT_MAX_BODY_BYTES) {
        val previous = this.sink
        if (previous !== sink) (previous as? AutoCloseable)?.close()
        this.sink = sink
        this.maxBodyBytes = maxBodyBytes
    }

    /**
     * Invoked by woven bytecode immediately before `OkHttpClient.Builder.build()`.
     *
     * Idempotent: adds a [WailoInterceptor] only when none is present, so a client that was *also*
     * wired manually is captured exactly once. Before [install], or when no sink is set, it is a no-op
     * and returns the builder untouched.
     */
    @JvmStatic
    fun hook(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val activeSink = sink ?: return builder
        if (builder.interceptors().none { it is WailoInterceptor }) {
            builder.addInterceptor(WailoInterceptor(activeSink, maxBodyBytes))
        }
        return builder
    }
}
