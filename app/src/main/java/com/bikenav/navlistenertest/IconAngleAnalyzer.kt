package com.bikenav.navlistenertest

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.cos

/**
 * Measures the roundabout exit angle directly from the icon bitmap Google
 * Maps drew, instead of learning it from text ("2nd exit") and looking it up
 * by a coarse 64-bit perceptual hash.
 *
 * Why the old approach broke: IconHasher.dHash() shrinks every icon to 9x8
 * grayscale before hashing. A left-turn arrow and a right-turn arrow are
 * visually very different at that resolution, so hash-based lookup is
 * reliable for them - that's why plain turns ("90 and all") always worked.
 * But every roundabout icon is the SAME circle with the SAME entry stub,
 * differing only by a small arrow rotated a few degrees for each exit.
 * Shrunk to 9x8, adjacent exits (1st vs 2nd vs 3rd...) often hash to
 * near-identical values, so the tolerant lookup would confidently return
 * the WRONG learned exit - and that learned value itself came from a text
 * regex guess in the first place. Two unreliable steps stacked on each other.
 *
 * This analyzer removes both problems: it looks at the actual bitmap, at
 * full resolution, every time a notification arrives - no caching, no
 * hash tolerance, no text regex anywhere in the path.
 *
 * Method:
 *  1. Downscale for consistent noise behaviour, then build a foreground mask
 *     (icon strokes) using alpha (if the icon has transparency) or a
 *     luminance difference from the background color otherwise.
 *  2. Take the bounding-box center of the foreground mask as the icon center
 *     (the circle sits centered in the glyph, so this is a good stand-in for
 *     the true circle center).
 *  3. Google Maps always draws the "you are here" entry line as a fixed
 *     stub at the bottom of the icon (straight up into the circle), which is
 *     identical across every exit and would otherwise dominate a naive
 *     "farthest pixel" search. Exclude a fixed wedge around straight-down
 *     before looking for the exit arrow.
 *  4. Within what's left, the exit arrowhead is the cluster of foreground
 *     pixels farthest from the center (it's the only part of the icon that
 *     pokes outward at a variable angle). Take the circular mean angle of
 *     the farthest slice of pixels as the measured exit angle.
 *  5. Snap to the nearest 45 degrees to match the firmware's 8-direction
 *     rendering and stay stable against single-pixel noise.
 *
 * Angle convention matches NavAccessibilityService's roundaboutAngleDeg:
 * 0=straight, 90=right, 180=back, 270=left.
 */
object IconAngleAnalyzer {

    private const val SAMPLE_SIZE = 48
    private const val MIN_FOREGROUND_PIXELS = 15
    // Half-width of the wedge excluded around straight-down (180deg) to
    // ignore the fixed entry stub every roundabout icon shares.
    private const val ENTRY_STUB_EXCLUSION_DEG = 35.0
    private const val FARTHEST_FRACTION = 0.2

    /**
     * Returns the measured exit angle in degrees [0, 360), or null if the
     * bitmap doesn't have enough foreground signal to trust (e.g. a blank
     * or malformed icon) - callers should fall back to whatever text-derived
     * angle is available rather than send a fabricated reading.
     */
    fun analyzeExitAngle(bitmap: Bitmap): Int? {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        try {
            val fg = foregroundPixels(sample)
            if (fg.size < MIN_FOREGROUND_PIXELS) return null

            var minX = SAMPLE_SIZE; var maxX = 0
            var minY = SAMPLE_SIZE; var maxY = 0
            for ((x, y) in fg) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val cx = (minX + maxX) / 2.0
            val cy = (minY + maxY) / 2.0

            data class Pt(val dist: Double, val angleDeg: Double)
            val pts = fg.map { (x, y) ->
                val dx = x - cx
                val dy = y - cy
                var angle = Math.toDegrees(atan2(dx, -dy)) // 0=up, 90=right, 180=down, 270=left
                if (angle < 0) angle += 360.0
                Pt(hypot(dx, dy), angle)
            }

            fun angularDistFrom180(a: Double): Double {
                val d = abs(((a - 180.0 + 540.0) % 360.0) - 180.0)
                return d
            }

            val withoutEntryStub = pts.filter { angularDistFrom180(it.angleDeg) > ENTRY_STUB_EXCLUSION_DEG }
            val pool = if (withoutEntryStub.size >= 5) withoutEntryStub else pts
            if (pool.isEmpty()) return null

            val takeCount = (pool.size * FARTHEST_FRACTION).toInt().coerceAtLeast(3).coerceAtMost(pool.size)
            val farthest = pool.sortedByDescending { it.dist }.take(takeCount)

            var sx = 0.0
            var sy = 0.0
            for (p in farthest) {
                val rad = Math.toRadians(p.angleDeg)
                sx += sin(rad)
                sy += cos(rad)
            }
            if (sx == 0.0 && sy == 0.0) return null // perfectly cancelled out, no clear direction

            var meanAngle = Math.toDegrees(atan2(sx, sy))
            if (meanAngle < 0) meanAngle += 360.0

            val snapped = (round(meanAngle / 45.0).toInt() * 45) % 360
            return snapped
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun foregroundPixels(bitmap: Bitmap): List<Pair<Int, Int>> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hasAlphaChannel = pixels.any { ((it ushr 24) and 0xFF) in 1..254 }

        val result = ArrayList<Pair<Int, Int>>()
        if (hasAlphaChannel) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    if (((p ushr 24) and 0xFF) > 96) result.add(x to y)
                }
            }
            return result
        }

        // No usable alpha (opaque icon) - fall back to luminance difference
        // from an estimated background color, sampled from the four corners.
        val corners = listOf(
            pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[(h - 1) * w + w - 1]
        )
        val bgLum = corners.map { luminance(it) }.average()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (abs(luminance(p) - bgLum) > 40) result.add(x to y)
            }
        }
        return result
    }

    private fun luminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return r * 0.299 + g * 0.587 + b * 0.114
    }
}
