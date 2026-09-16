package com.openlink.child.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the pairing URI to a QR bitmap on-device.
 *
 * Only ZXing's `core` artifact is used -- it is pure Java with no Android dependencies, so the
 * pixel plotting is ours. Nothing about pairing ever leaves the device, including the image.
 */
object QrCodeEncoder {

    /**
     * @param sizePx the side length of the square bitmap to produce.
     * @return the QR bitmap, or null if the payload could not be encoded (only realistically
     *         possible if the endpoint list grew past QR capacity, which the caller handles by
     *         showing the URI as text instead).
     */
    fun encode(contents: String, sizePx: Int): Bitmap? {
        if (contents.isEmpty() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                // Level M survives a scuffed screen or a bad camera angle without inflating the
                // symbol as much as Q/H would; the payload is already ~250 characters.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
