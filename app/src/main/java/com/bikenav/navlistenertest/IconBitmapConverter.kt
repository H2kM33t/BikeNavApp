package com.bikenav.navlistenertest

import android.graphics.Bitmap

/**
 * Converts the actual roundabout icon bitmap Google Maps drew into its
 * notification (see IconExtractor) into a small 1-bit bitmap the ESP32 can
 * blit directly onto the OLED with u8g2's drawXBMP().
 *
 * This replaces trying to *compute* the exit angle and redraw our own
 * arrow (IconAngleAnalyzer) with just forwarding Google's own pixels,
 * mirroring how maisonsmd's esp32-google-maps project handles turn icons.
 * Google has already solved "what does a 3rd-exit roundabout look like" -
 * there's no need to reverse-engineer it from a hash or an angle estimate.
 *
 * Output format is XBM byte order: LSB-first within each byte, row-major,
 * width padded to a byte boundary (ICON_SIZE=32 is already a multiple of
 * 8, so no padding is actually needed here) - this is exactly what
 * u8g2.drawXBMP(x, y, w, h, bitmap) expects as its bitmap argument.
 */
object IconBitmapConverter {
    // Must match the icon box size drawn on the ESP32 (iconSize in
    // renderNavigationActive(), currently 32x32).
    const val ICON_SIZE = 32
    private const val BYTES_PER_ROW = ICON_SIZE / 8 // 4
    const val PACKED_SIZE = BYTES_PER_ROW * ICON_SIZE // 128 bytes

    /**
     * threshold on alpha rather than color/luminance: Google's TBT icons are
     * opaque glyph pixels over a fully transparent background regardless of
     * light/dark theme, so "is this pixel part of the glyph" is really "is
     * this pixel non-transparent" - which is theme-proof, unlike a
     * brightness threshold that would invert between light and dark mode.
     */
    private const val ALPHA_ON_THRESHOLD = 100

    fun toXbmBytes(source: Bitmap): ByteArray {
        val scaled = if (source.width == ICON_SIZE && source.height == ICON_SIZE) {
            source
        } else {
            Bitmap.createScaledBitmap(source, ICON_SIZE, ICON_SIZE, true)
        }

        val out = ByteArray(PACKED_SIZE)
        for (y in 0 until ICON_SIZE) {
            for (xByte in 0 until BYTES_PER_ROW) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    val pixel = scaled.getPixel(x, y)
                    val alpha = (pixel ushr 24) and 0xFF
                    if (alpha > ALPHA_ON_THRESHOLD) {
                        b = b or (1 shl bit) // LSB-first, matches XBM byte order
                    }
                }
                out[y * BYTES_PER_ROW + xByte] = b.toByte()
            }
        }

        if (scaled !== source) scaled.recycle()
        return out
    }
}
