package com.venbiasa.wailo.sdk.android

import com.venbiasa.wailo.core.CaptureSink
import com.venbiasa.wailo.core.WailoClient
import com.venbiasa.wailo.protocol.Hello
import okhttp3.Interceptor

/** Install Wailo by adding [interceptor] to an OkHttpClient. */
object Wailo {
    /** Per-body capture cap; larger bodies are truncated, not dropped. */
    const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024L

    fun interceptor(
        sink: CaptureSink = LogcatSink(),
        maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    ): Interceptor = WailoInterceptor(sink, maxBodyBytes)

    /** Sink that streams to the desktop; call [WailoClient.start] before use. */
    fun webSocketSink(
        appId: String,
        deviceName: String,
        host: String = WailoClient.DEFAULT_HOST,
        port: Int = WailoClient.DEFAULT_PORT,
    ): WailoClient = WailoClient(
        hello = Hello(device_name = deviceName, app_id = appId, platform = PLATFORM_ANDROID),
        host = host,
        port = port,
    )

    private const val PLATFORM_ANDROID: String = "android"
}
