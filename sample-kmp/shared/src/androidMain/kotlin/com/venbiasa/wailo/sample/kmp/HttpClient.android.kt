package com.venbiasa.wailo.sample.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

// OkHttp engine: its OkHttpClient.build() is rewritten by the Wailo ASM plugin, so requests are
// captured without any Wailo reference in shared code. Ktor CIO would NOT be captured.
internal actual fun createHttpClient(): HttpClient = HttpClient(OkHttp)
