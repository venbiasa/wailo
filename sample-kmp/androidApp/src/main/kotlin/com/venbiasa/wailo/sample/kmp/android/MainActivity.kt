package com.venbiasa.wailo.sample.kmp.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.venbiasa.wailo.sample.kmp.SampleApi
import com.venbiasa.wailo.sdk.android.LogcatSink
import com.venbiasa.wailo.sdk.android.Wailo
import com.venbiasa.wailo.sdk.android.WailoRuntime
import com.venbiasa.wailo.sdk.android.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wsSink by lazy {
        Wailo.webSocketSink(appId = packageName, deviceName = Build.MODEL).also { it.start() }
    }
    // Same shared Kotlin API the iOS app uses; it has no idea Wailo is capturing it.
    private val api = SampleApi()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed the process-global sink auto-instrumented clients report to. This is the
        // only Wailo wiring the KMP app needs on Android; `shared` stays Wailo-free.
        WailoRuntime.install(wsSink + LogcatSink())

        output = TextView(this).apply { setPadding(PADDING, PADDING, PADDING, PADDING) }
        val send = Button(this).apply {
            text = "Send requests"
            setOnClickListener { sendRequests() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    send,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    ScrollView(this@MainActivity).apply { addView(output) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    // Fired on tap (parity with the iOS sample) so requests can be re-sent and watched on the desktop.
    private fun sendRequests() {
        scope.launch {
            append(api.fetchTodo())
            append(api.createPost())
        }
    }

    private fun append(line: String) = runOnUiThread { output.append(line + "\n") }

    override fun onDestroy() {
        scope.cancel()
        wsSink.stop()
        super.onDestroy()
    }

    private companion object {
        const val PADDING = 48
    }
}
