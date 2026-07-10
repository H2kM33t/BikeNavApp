package com.bikenav.navlistenertest

import android.content.Context
import android.content.SharedPreferences

/**
 * Self-training hash -> turnCode table. Every time the text classifier is
 * CONFIDENT about a maneuver (unambiguous "turn left", "sharp right", etc.),
 * we teach it the icon-hash Maps showed for that maneuver. On every
 * notification - confident or not - we look the current hash up here first.
 * Since Maps reuses the same icon bitmap for the same maneuver every time,
 * this means an ambiguous or unrecognized phrasing on turn #40 gets
 * corrected for free if turn #3 already taught us that exact icon.
 *
 * This only handles the coarse maneuver category (left/right/straight/
 * roundabout/fork/...), where shapes are visually distinct enough for a
 * tolerant 64-bit hash match to be reliable. It intentionally does NOT
 * handle the roundabout exit angle anymore — different exits are too
 * visually similar at hash resolution for that, and teaching the table
 * from text baked in whatever the text regex got wrong. The exit angle is
 * now measured directly from the icon's pixels every time, in
 * IconAngleAnalyzer, with no hash lookup and no text involved.
 *
 * Persisted across app restarts so it only needs to "learn" once, not once
 * per ride.
 */
object IconLearner {
    private const val PREFS_NAME = "icon_learner"
    private const val KEY_MAP = "hash_to_turn"

    // Two icons for genuinely different maneuvers can differ by only a few
    // pixels at thumbnail resolution (e.g. slight-left vs sharp-left), so
    // this stays tight - tuned to tolerate anti-aliasing/theme noise without
    // conflating distinct icons. Widen only if false negatives (icon known,
    // but distance stays juuust above the threshold) show up in logs.
    private const val MAX_HAMMING_DISTANCE = 4

    private lateinit var prefs: SharedPreferences
    private val cache = mutableMapOf<Long, Int>()
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cache.clear()
        prefs.getString(KEY_MAP, null)?.split(";")?.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(":")
            if (parts.size == 2) {
                val hash = parts[0].toLongOrNull()
                val turn = parts[1].toIntOrNull()
                if (hash != null && turn != null) cache[hash] = turn
            }
        }
        initialized = true
        NavLog.post("IconLearner: loaded ${cache.size} turn icon(s)")
    }

    @Synchronized
    fun learn(hash: Long, turnCode: Int) {
        if (!initialized) return
        val prev = cache[hash]
        cache[hash] = turnCode
        if (prev != turnCode) {
            persist()
            NavLog.post("IconLearner: learned hash=$hash -> turnCode=$turnCode")
        }
    }

    /** Exact match first, then nearest known hash within tolerance. */
    @Synchronized
    fun lookup(hash: Long): Int? {
        if (!initialized) return null
        cache[hash]?.let { return it }
        var bestTurn: Int? = null
        var bestDist = Int.MAX_VALUE
        for ((h, t) in cache) {
            val d = IconHasher.hammingDistance(h, hash)
            if (d < bestDist) {
                bestDist = d
                bestTurn = t
            }
        }
        return if (bestDist <= MAX_HAMMING_DISTANCE) bestTurn else null
    }

    private fun persist() {
        val serialized = cache.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_MAP, serialized).apply()
    }

    /** Wipes learned icons — expose this behind a Settings button if icons ever get mis-learned. */
    @Synchronized
    fun clear() {
        cache.clear()
        if (initialized) prefs.edit().remove(KEY_MAP).apply()
    }
}
