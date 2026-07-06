package com.venbiasa.waylay.sdk.android

import com.venbiasa.waylay.core.CaptureSink
import com.venbiasa.waylay.core.WaylayClient
import com.venbiasa.waylay.protocol.Hello
import okhttp3.Interceptor

/** Install Waylay by adding [interceptor] to an OkHttpClient. */
object Waylay {
    /** Per-body capture cap; larger bodies are truncated, not dropped. */
    const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024L

    fun interceptor(
        sink: CaptureSink = LogcatSink(),
        maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    ): Interceptor = WaylayInterceptor(sink, maxBodyBytes)

    /** Sink that streams to the desktop; call [WaylayClient.start] before use. */
    fun webSocketSink(
        appId: String,
        deviceName: String,
        host: String = WaylayClient.DEFAULT_HOST,
        port: Int = WaylayClient.DEFAULT_PORT,
    ): WaylayClient = WaylayClient(
        hello = Hello(device_name = deviceName, app_id = appId, platform = PLATFORM_ANDROID),
        host = host,
        port = port,
    )

    private const val PLATFORM_ANDROID: String = "android"
}
