package com.venbiasa.wailo.desktop

import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

/**
 * The OS-level app events that only matter once Studio can run with its window hidden behind the menu
 * bar item. Every call is optional and guarded: these are macOS features in practice, and an
 * unsupported platform must degrade to "nothing happens", never to a crash on launch.
 */
internal object DesktopAppEvents {
    private val desktop: Desktop? =
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()

    /**
     * Clicking the Dock icon of an app with no open window. Without this the icon is inert once the
     * window is hidden, and the menu bar item becomes the only way back in.
     */
    fun addReopenedListener(onReopened: () -> Unit): AutoCloseable? {
        val target = supporting(Desktop.Action.APP_EVENT_REOPENED) ?: return null
        val listener = AppReopenedListener { onReopened() }
        return runCatching {
            target.addAppEventListener(listener)
            AutoCloseable { runCatching { target.removeAppEventListener(listener) } }
        }.getOrNull()
    }

    /**
     * Cmd-Q and the app menu's Quit. The default handler ends the JVM outright, skipping the window's own
     * close path — so a resize inside the geometry debounce would be lost. The request is cancelled and
     * handed to [onQuit] instead.
     */
    fun setQuitHandler(onQuit: () -> Unit): AutoCloseable? {
        val target = supporting(Desktop.Action.APP_QUIT_HANDLER) ?: return null
        return runCatching {
            target.setQuitHandler { _, response ->
                response.cancelQuit()
                onQuit()
            }
            AutoCloseable { runCatching { target.setQuitHandler(null) } }
        }.getOrNull()
    }

    /** Raises Wailo above other applications; `toFront` alone cannot when Wailo is not the active app. */
    fun requestForeground() {
        supporting(Desktop.Action.APP_REQUEST_FOREGROUND)?.let {
            runCatching { it.requestForeground(false) }
        }
    }

    private fun supporting(action: Desktop.Action): Desktop? =
        desktop?.takeIf { runCatching { it.isSupported(action) }.getOrDefault(false) }
}
