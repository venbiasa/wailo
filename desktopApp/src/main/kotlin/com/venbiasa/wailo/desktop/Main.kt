package com.venbiasa.wailo.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.shared.FlowEntry
import com.venbiasa.wailo.shared.WailoApp
import java.util.TimeZone

fun main() = application {
    val engine = remember { WailoEngine().also(WailoEngine::start) }
    val rows by engine.exchanges.collectAsState()

    // Map the engine's rows into the viewer's model at this boundary — `shared` must not depend on
    // `engine` (module firewall), so the two `Captured*` types are bridged here rather than shared.
    val entries = remember(rows) {
        rows.map { FlowEntry(it.deviceName, it.appId, it.platform, it.exchange) }
    }
    // The viewer formats timestamps in commonMain (no java.time), so pass the host's zone offset.
    val zoneOffsetMillis = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) }

    Window(onCloseRequest = ::exitApplication, title = "Wailo") {
        WailoApp(entries = entries, zoneOffsetMillis = zoneOffsetMillis)
    }
}
