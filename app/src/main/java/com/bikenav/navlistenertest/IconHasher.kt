package com.bikenav.navlistenertest

import android.graphics.Bitmap

/**
 * Perceptual hash for turn-icon bitmaps. Google Maps re-draws the exact same
 * icon (same pixels, modulo minor anti-aliasing/theme-color noise) every
 * time it shows a given maneuver, so a difference-hash (dHash) of that icon
 * is a far more reliable maneuver fingerprint than parsing the instruction
 * string - it doesn't care about phrasing, language, or "then " lookahead
 * previews at all.
 */
object IconHasher {

    /**
     * Computes a 64-bit dHash: shrink to 9x8 grayscale, then for each row
     * record whether each pixel is brighter than the one to its right. Small
     * rendering differences (anti-aliasing, minor color theme changes)
     * flip at most a handful of bits, which is exactly what the tolerance
     * in IconLearner.lookup() is for.
     */
    fun dHash(bitmap: Bitmap): Long {
        val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val g1 = gray(resized.getPixel(x, y))
                val g2 = gray(resized.getPixel(x + 1, y))
                if (g1 > g2) hash = hash or (1L shl bit)
                bit++
            }
        }
        if (resized !== bitmap) resized.recycle()
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun gray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
