package com.venbiasa.waylay.sample

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.venbiasa.waylay.core.plus
import com.venbiasa.waylay.sdk.android.LogcatSink
import com.venbiasa.waylay.sdk.android.Waylay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : ComponentActivity() {

    // Streams to the desktop over adb reverse (localhost:8899) and mirrors to Logcat.
    private val wsSink by lazy {
        Waylay.webSocketSink(appId = packageName, deviceName = Build.MODEL).also { it.start() }
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Waylay.interceptor(sink = wsSink + LogcatSink()))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val results = remember { mutableStateListOf<String>() }
            LaunchedEffect(Unit) { runSampleTraffic(results) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Waylay sample", style = MaterialTheme.typography.h6)
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(results) { line -> Text(line, style = MaterialTheme.typography.body2) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        wsSink.stop()
        super.onDestroy()
    }

    private fun runSampleTraffic(results: SnapshotStateList<String>) {
        sampleRequests().forEach { request ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    post(results, "${request.method} ${request.url} -> ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { post(results, "${request.method} ${request.url} -> HTTP ${it.code}") }
                }
            })
        }
    }

    private fun post(results: SnapshotStateList<String>, line: String) {
        runOnUiThread { results.add(line) }
    }

    private fun sampleRequests(): List<Request> = listOf(
        Request.Builder()
            .url("https://jsonplaceholder.typicode.com/todos/1")
            .build(),
        Request.Builder()
            .url("https://jsonplaceholder.typicode.com/posts")
            .post("""{"title":"waylay","body":"hello","userId":1}""".toRequestBody(JSON))
            .build(),
    )

    private companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
