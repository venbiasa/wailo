package com.venbiasa.wailo.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

internal object QrCode {
    fun render(payload: String): ImageBitmap? = runCatching {
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    setRGB(x, y, if (matrix.get(x, y)) BLACK else WHITE)
                }
            }
        }.toComposeImageBitmap()
    }.getOrNull()

    private const val QUIET_ZONE_MODULES = 2
    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF
}
