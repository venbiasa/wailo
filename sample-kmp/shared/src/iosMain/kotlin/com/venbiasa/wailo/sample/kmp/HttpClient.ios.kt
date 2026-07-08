package com.venbiasa.wailo.sample.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

// Darwin engine is built on URLSession, so sdk-ios's URLProtocol swizzle (installed by Wailo.start()
// in the Swift app shell) captures these requests with no Wailo reference in shared code.
internal actual fun createHttpClient(): HttpClient = HttpClient(Darwin)
