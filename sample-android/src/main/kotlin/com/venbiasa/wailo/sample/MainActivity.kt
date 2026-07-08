package com.venbiasa.wailo.sample

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
import androidx.compose.material.Button
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.venbiasa.wailo.sdk.android.plus
import com.venbiasa.wailo.sdk.android.LogcatSink
import com.venbiasa.wailo.sdk.android.Wailo
import com.venbiasa.wailo.sdk.android.WailoRuntime
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
        Wailo.webSocketSink(appId = packageName, deviceName = Build.MODEL).also { it.start() }
    }
    private val sink by lazy { wsSink + LogcatSink() }

    // No Wailo interceptor here: the Gradle plugin rewrites this build() call site to route through
    // WailoRuntime, so this "third-party-style" client is captured purely by auto-instrumentation.
    private val autoClient by lazy { OkHttpClient.Builder().build() }

    // Manually wired *and* auto-instrumented: proves the hook is idempotent (captured once, not twice).
    private val manualClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Wailo.interceptor(sink = sink))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed the process-global sink auto-instrumented clients report to.
        WailoRuntime.install(sink)
        setContent {
            val results = remember { mutableStateListOf<String>() }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Wailo sample", style = MaterialTheme.typography.h6)
                        // Fire on tap (parity with the iOS sample) so traffic can be re-sent and
                        // watched streaming to the desktop, rather than firing once at launch.
                        Button(
                            onClick = { runSampleTraffic(results) },
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            Text("Send requests")
                        }
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
        // Auto-instrumented client (no manual interceptor): captured via the Gradle plugin.
        fire(autoClient, getRequest("https://jsonplaceholder.typicode.com/todos/1"), "auto", results)
        fire(autoClient, postRequest(), "auto", results)
        // Manually wired *and* instrumented: expected to appear exactly once (idempotent hook).
        fire(manualClient, getRequest("https://jsonplaceholder.typicode.com/todos/2"), "manual", results)
    }

    private fun fire(
        client: OkHttpClient,
        request: Request,
        label: String,
        results: SnapshotStateList<String>,
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                post(results, "[$label] ${request.method} ${request.url} -> ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { post(results, "[$label] ${request.method} ${request.url} -> HTTP ${it.code}") }
            }
        })
    }

    private fun post(results: SnapshotStateList<String>, line: String) {
        runOnUiThread { results.add(line) }
    }

    private fun getRequest(url: String): Request = Request.Builder().url(url).build()

    private fun postRequest(): Request = Request.Builder()
        .url("https://jsonplaceholder.typicode.com/posts")
        .post("""{"title":"wailo","body":"hello","userId":1}""".toRequestBody(JSON))
        .build()

    private companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
