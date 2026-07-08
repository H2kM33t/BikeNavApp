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
 * Persisted across app restarts so it only needs to "learn" once, not once
 * per ride.
 */
object IconLearner {
    private const val PREFS_NAME = "icon_learner"
    private const val KEY_MAP = "hash_to_turn"
    private const val KEY_ANGLE_MAP = "hash_to_angle"

    // Two icons for genuinely different maneuvers can differ by only a few
    // pixels at thumbnail resolution (e.g. slight-left vs sharp-left), so
    // this stays tight - tuned to tolerate anti-aliasing/theme noise without
    // conflating distinct icons. Widen only if false negatives (icon known,
    // but distance stays juuust above the threshold) show up in logs.
    private const val MAX_HAMMING_DISTANCE = 4

    private lateinit var prefs: SharedPreferences
    private val cache = mutableMapOf<Long, Int>()
    private val angleCache = mutableMapOf<Long, Int>()
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
        angleCache.clear()
        prefs.getString(KEY_ANGLE_MAP, null)?.split(";")?.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(":")
            if (parts.size == 2) {
                val hash = parts[0].toLongOrNull()
                val angle = parts[1].toIntOrNull()
                if (hash != null && angle != null) angleCache[hash] = angle
            }
        }
        initialized = true
        NavLog.post("IconLearner: loaded ${cache.size} turn icon(s), ${angleCache.size} roundabout angle icon(s)")
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

    /**
     * Learns which exact exit angle a roundabout icon corresponds to. Unlike
     * the turn code (which only needs left/right), the angle is exact
     * degrees, so this is only learned when the accessibility text gave a
     * CONFIDENT angle (i.e. it actually found "2nd exit" etc. in the text,
     * not one of the generic straight/left/right/else fallback guesses).
     */
    @Synchronized
    fun learnAngle(hash: Long, angleDeg: Int) {
        if (!initialized) return
        val prev = angleCache[hash]
        angleCache[hash] = angleDeg
        if (prev != angleDeg) {
            persistAngles()
            NavLog.post("IconLearner: learned roundabout angle hash=$hash -> ${angleDeg}deg")
        }
    }

    @Synchronized
    fun lookupAngle(hash: Long): Int? {
        if (!initialized) return null
        angleCache[hash]?.let { return it }
        var bestAngle: Int? = null
        var bestDist = Int.MAX_VALUE
        for ((h, a) in angleCache) {
            val d = IconHasher.hammingDistance(h, hash)
            if (d < bestDist) {
                bestDist = d
                bestAngle = a
            }
        }
        return if (bestDist <= MAX_HAMMING_DISTANCE) bestAngle else null
    }

    private fun persistAngles() {
        val serialized = angleCache.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_ANGLE_MAP, serialized).apply()
    }

    private fun persist() {
        val serialized = cache.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_MAP, serialized).apply()
    }

    /** Wipes learned icons — expose this behind a Settings button if icons ever get mis-learned. */
    @Synchronized
    fun clear() {
        cache.clear()
        angleCache.clear()
        if (initialized) prefs.edit().remove(KEY_MAP).remove(KEY_ANGLE_MAP).apply()
    }
}
