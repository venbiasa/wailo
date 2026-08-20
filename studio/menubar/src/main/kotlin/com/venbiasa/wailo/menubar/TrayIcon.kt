package com.venbiasa.wailo.menubar

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The item's two looks: the hollow mark, and the filled tile it wears while recording. */
internal class TrayImages(val idle: BufferedImage, val recording: BufferedImage)

/**
 * Where the menu bar / notification area item gets its pictures.
 *
 * macOS wants a *template* image: shape in the alpha channel, no colour, which the system then tints to
 * whatever the menu bar currently is. Only the system can make that call — the bar goes dark over a dark
 * wallpaper while the OS is still in light mode, so a glyph coloured from `AppleInterfaceStyle` comes out
 * black on black exactly when it matters. The agent hands the image over as a template (see
 * `apple.awt.enableTemplateImages` in Main.kt), so both assets are pure alpha over black. Windows and
 * Linux tray areas are icon-coloured by convention and have no such tint to invert against, so they get
 * the app mark itself and one look for both states.
 *
 * The two macOS assets are drawn for the menu bar rather than scaled down from the app mark, whose 1024²
 * strokes land under a pixel wide at the ~20pt a bar reports. Nothing couples them to that mark, so a new
 * one means redrawing these two by hand. They are authored at 2x (40px for a 20pt bar), which a Retina
 * bar maps one-for-one; a 1x display gets a clean halving.
 */
internal object TrayIcon {
    private val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    /** Null when this install ships no icon at all, which leaves the agent nothing to draw. */
    fun load(): TrayImages? = if (isMac) {
        val idle = read(IDLE_RESOURCE)
        val recording = read(RECORDING_RESOURCE)
        if (idle != null && recording != null) TrayImages(idle, recording) else null
    } else {
        read(MARK_RESOURCE)?.let { TrayImages(idle = it, recording = it) }
    }

    private fun read(resource: String): BufferedImage? = runCatching {
        TrayIcon::class.java.getResourceAsStream(resource)?.use(ImageIO::read)
    }.getOrNull()

    private const val MARK_RESOURCE = "/icons/wailo.png"
    private const val IDLE_RESOURCE = "/icons/tray-idle.png"
    private const val RECORDING_RESOURCE = "/icons/tray-recording.png"
}
