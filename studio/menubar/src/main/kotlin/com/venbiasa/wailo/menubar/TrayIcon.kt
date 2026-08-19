package com.venbiasa.wailo.menubar

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The menu bar / notification area icon, derived from the one app mark rather than shipped as a second
 * asset — so the two can never drift. The mark itself still lives with the app it belongs to; this
 * module's build copies it in (see build.gradle.kts).
 *
 * macOS wants a *template* image: shape in the alpha channel, no colour, which the system then tints to
 * whatever the menu bar currently is. Only the system can make that call — the bar goes dark over a dark
 * wallpaper while the OS is still in light mode, so a glyph coloured from `AppleInterfaceStyle` comes out
 * black on black exactly when it matters. So this only builds the shape (alpha from the mark's brightest
 * channel, which drops the mark's near-black tile and keeps the blue detour and white arrow), and the
 * agent hands it over as a template (see `apple.awt.enableTemplateImages` in Main.kt). Windows and Linux
 * tray areas are icon-coloured by convention, so they get the mark as it is.
 */
internal object TrayIcon {
    private val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    fun mark(): BufferedImage? = runCatching {
        TrayIcon::class.java.getResourceAsStream(MARK_RESOURCE)?.use(ImageIO::read)
    }.getOrNull()

    fun render(mark: BufferedImage): BufferedImage = if (isMac) silhouette(mark, TEMPLATE_RGB) else mark

    private fun silhouette(mark: BufferedImage, rgb: Int): BufferedImage {
        // Derive from a downscaled copy: the mark is 1024², the tray draws at ~22, and two full-resolution
        // passes would cost more than the result could ever show.
        val source = scaledTo(mark, WORKING_SIDE)
        val mask = alphaMask(source)
        val floor = (mask.peak * GLYPH_FLOOR_FRACTION).toInt()
        val bounds = opaqueBounds(mask, floor) ?: return mark
        // Square the crop before padding so a wide mark is not stretched to fill the icon's square canvas.
        val side = maxOf(bounds.width, bounds.height)
        val padding = (side * MARGIN_FRACTION).toInt()
        val canvas = side + padding * 2
        val out = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
        val left = padding + (side - bounds.width) / 2
        val top = padding + (side - bounds.height) / 2
        for (y in 0 until bounds.height) {
            for (x in 0 until bounds.width) {
                val alpha = mask[bounds.x + x, bounds.y + y]
                if (alpha > floor) out.setRGB(left + x, top + y, (alpha shl 24) or rgb)
            }
        }
        return out
    }

    private fun scaledTo(mark: BufferedImage, side: Int): BufferedImage {
        if (mark.width <= side && mark.height <= side) return mark
        val scale = side.toDouble() / maxOf(mark.width, mark.height)
        val out = BufferedImage(
            (mark.width * scale).toInt().coerceAtLeast(1),
            (mark.height * scale).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val graphics = out.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(mark, 0, 0, out.width, out.height, null)
        } finally {
            graphics.dispose()
        }
        return out
    }

    private fun alphaMask(mark: BufferedImage): Mask {
        val alpha = IntArray(mark.width * mark.height)
        var peak = 0
        for (y in 0 until mark.height) {
            for (x in 0 until mark.width) {
                val pixel = mark.getRGB(x, y)
                val source = pixel ushr 24
                if (source == 0) continue
                val brightest = maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
                val value = brightest * source / 0xFF
                alpha[y * mark.width + x] = value
                if (value > peak) peak = value
            }
        }
        return Mask(alpha, mark.width, mark.height, peak)
    }

    private fun opaqueBounds(mask: Mask, floor: Int): Bounds? {
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask[x, y] <= floor) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        return Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private class Mask(private val alpha: IntArray, val width: Int, val height: Int, val peak: Int) {
        operator fun get(x: Int, y: Int): Int = alpha[y * width + x]
    }

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private const val MARK_RESOURCE = "/icons/wailo.png"

    // A template image is read for its alpha alone, so the fill only has to be *a* colour; black is the
    // one that still looks like an icon anywhere the template flag does not apply.
    private const val TEMPLATE_RGB = 0x000000

    // macOS menu bar glyphs sit in a canvas with visible breathing room; filling it edge to edge reads
    // as too big next to every system item.
    private const val MARGIN_FRACTION = 0.1

    // What counts as glyph rather than backdrop, as a share of the mask's own peak. Relative because the
    // mark's tile is *near* black, not black: an absolute floor low enough to keep antialiased glyph edges
    // also keeps the tile, which both draws a ghost of it and pads the crop until the glyph is a smudge —
    // and any floor tuned to this one asset would break on the next mark.
    private const val GLYPH_FLOOR_FRACTION = 0.15
    private const val WORKING_SIDE = 128
}
