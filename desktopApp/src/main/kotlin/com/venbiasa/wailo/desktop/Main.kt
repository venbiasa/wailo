package com.venbiasa.wailo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.WailoEngine

fun main() = application {
    val engine = remember { WailoEngine().also(WailoEngine::start) }
    val exchanges by engine.exchanges.collectAsState()

    Window(onCloseRequest = ::exitApplication, title = "Wailo") {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Captured requests (${exchanges.size})", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))
                    if (exchanges.isEmpty()) {
                        Text("Waiting for traffic on :${WailoEngine.DEFAULT_PORT} …")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(exchanges.asReversed()) { row ->
                                Text(row.asLine(), style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun CapturedExchange.asLine(): String {
    val request = exchange.request
    val method = request?.method ?: "?"
    val url = request?.url ?: "?"
    val response = exchange.response
    val outcome = when {
        response != null -> response.code.toString()
        exchange.error.isNotEmpty() -> "ERR ${exchange.error}"
        else -> "…"
    }
    return "[$appId] $method $url → $outcome (${exchange.duration_ms}ms)"
}
