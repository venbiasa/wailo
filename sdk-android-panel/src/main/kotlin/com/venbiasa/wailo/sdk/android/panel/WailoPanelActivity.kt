package com.venbiasa.wailo.sdk.android.panel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier

/**
 * The on-device panel for re-pointing Wailo at a different desktop, and for pairing with one over Wi-Fi.
 *
 * Declared with a launcher entry of its own, so it needs no host code at all: opening it cold-starts the
 * host's process, which runs `WailoStartupProvider` and therefore has a live client behind it by the time
 * the panel draws. A host that would rather reach it from its own debug menu calls [WailoPanel.show].
 */
class WailoPanelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Insets are handled here rather than by the window: a host targeting SDK 35 or later gets
            // edge-to-edge forced on, which would otherwise put the title under the status bar.
            WailoPanelScreen(Modifier.systemBarsPadding().imePadding(), onClose = ::finish)
        }
    }
}

/**
 * Opens the panel.
 *
 * The launcher shortcut covers the usual case; this is for a host that wants it behind its own
 * affordance — a debug-menu row, a shake, a long-press — or that strips the shortcut from the manifest.
 */
object WailoPanel {

    @JvmStatic
    fun show(context: Context) {
        val intent = Intent(context, WailoPanelActivity::class.java)
        // An Application or Service context has no task to push onto, and starting without this flag
        // throws rather than degrading — which would only ever be found at the moment someone needs the
        // panel.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
