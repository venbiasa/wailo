package com.venbiasa.wailo.sample.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Shared networking used by both the Android and iOS apps. It knows nothing about Wailo — that is the
 * whole point: capture is wired at each platform's shell, never here. On Android the
 * OkHttp engine is auto-instrumented by the build-time plugin; on iOS the Darwin engine sits on
 * URLSession and is caught by sdk-ios's swizzle. Same shared code, captured on both.
 */
class SampleApi {

    private val client: HttpClient = createHttpClient()

    suspend fun fetchTodo(): String {
        val response: HttpResponse = client.get("https://jsonplaceholder.typicode.com/todos/1")
        return "GET /todos/1 -> HTTP ${response.status.value}"
    }

    suspend fun createPost(): String {
        val response: HttpResponse = client.post("https://jsonplaceholder.typicode.com/posts") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"wailo","body":"hello","userId":1}""")
        }
        return "POST /posts -> HTTP ${response.status.value}"
    }
}

/** Each platform supplies the Ktor engine Wailo can capture: OkHttp on Android, Darwin on iOS. */
internal expect fun createHttpClient(): HttpClient
