package com.bikenav.navlistenertest

import android.os.Handler
import android.os.Looper

/**
 * Single shared merge point for navigation data.
 *
 * Previously, NavNotificationListener and NavAccessibilityService each
 * classified the turn from their own text independently AND each called
 * BleNavClient.sendNavBytes() directly on their own event timers. Since both
 * fire on unrelated events (a notification update vs. an accessibility tree
 * change), whichever happened to fire LAST silently won and overwrote the
 * other on the ESP32 - even when it had guessed wrong. That race was very
 * likely a bigger source of "works 60-70% of the time then shows a false
 * turn" than either classifier's individual accuracy.
 *
 * Now both services only publish partial updates here; this object coalesces
 * updates that land close together in time and sends exactly one merged
 * packet, with turn-code resolved by trust priority rather than by whoever
 * happened to run last:
 *
 *   1) Icon-hash lookup (IconLearner) - fingerprints the actual icon Maps
 *      drew, immune to phrasing/language once learned.
 *   2) NavAccessibilityService's text parse - has its own corroboration/
 *      interlock against a single glitchy frame already.
 *   3) NavNotificationListener's text parse - simplest, no interlock, used
 *      only if neither of the above is available yet (e.g. right at the
 *      start of a ride before anything's been learned).
 */
object NavDataState {
    private val handler = Handler(Looper.getMainLooper())
    private const val COALESCE_MS = 300L
    private const val HEARTBEAT_INTERVAL_MS = 5000L

    @Volatile private var iconTurn: Int? = null
    @Volatile private var iconAngle: Int? = null

    @Volatile private var gpsSpeedKmh: Int? = null

    @Volatile private var accessTurn: Int? = null
    @Volatile private var accessInstruction: String = ""
    @Volatile private var accessDistToTurn: Int = 0
    @Volatile private var accessTotalDist: Int = 0
    @Volatile private var accessRemainDist: Int = 0
    @Volatile private var accessSpeedKmh: Int = 0
    @Volatile private var accessAngle: Int = 0
    @Volatile private var accessAngleConfident: Boolean = false
    @Volatile private var accessAngleConfidentAt: Long = 0L
    @Volatile private var accessAt: Long = 0L

    @Volatile private var notifTurn: Int? = null
    @Volatile private var notifInstruction: String = ""
    @Volatile private var notifDistToTurn: Int = 0
    @Volatile private var notifAt: Long = 0L

    private var lastSentSignature: String? = null
    private var lastPacket: ByteArray? = null
    private var pendingSend: Runnable? = null
    private var heartbeatRunning = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            lastPacket?.let { BleNavClient.sendNavBytes(it) }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        handler.post(heartbeatRunnable)
    }

    fun stopHeartbeat() {
        heartbeatRunning = false
        handler.removeCallbacks(heartbeatRunnable)
    }

    /** Called by GpsSpeedProvider whenever a fresh GPS fix reports speed. */
    fun updateGpsSpeed(speedKmh: Int) {
        gpsSpeedKmh = speedKmh
        scheduleSend()
    }

    /** Called by NavNotificationListener whenever it resolves an icon hash. */
    fun updateIcon(learnedTurn: Int?, learnedAngle: Int?) {
        iconTurn = learnedTurn
        iconAngle = learnedAngle
        scheduleSend()
    }

    /**
     * Called by NavNotificationListener with its own text-based read.
     * turnCode is null when the text classifier was UNKNOWN/ambiguous, so it
     * doesn't outrank a real accessibility or icon result.
     */
    fun updateFromNotification(turnCode: Int?, distToTurn: Int, instruction: String) {
        notifTurn = turnCode
        notifDistToTurn = distToTurn
        notifInstruction = instruction
        notifAt = System.currentTimeMillis()
        scheduleSend()
    }

    /** Called by NavAccessibilityService — the only source for speed/remaining/total/angle. */
    fun updateFromAccessibility(
        turnCode: Int,
        distToTurn: Int,
        totalDist: Int,
        remainDist: Int,
        speedKmh: Int,
        roundaboutAngleDeg: Int,
        angleConfident: Boolean,
        instruction: String
    ) {
        accessTurn = turnCode
        accessDistToTurn = distToTurn
        accessTotalDist = totalDist
        accessRemainDist = remainDist
        accessSpeedKmh = speedKmh
        accessAngle = roundaboutAngleDeg
        accessAngleConfident = angleConfident
        if (angleConfident) accessAngleConfidentAt = System.currentTimeMillis()
        accessInstruction = instruction
        accessAt = System.currentTimeMillis()
        scheduleSend()
    }

    /**
     * Returns the current roundabout angle only if it was just derived
     * confidently (an exact "Nth exit" match, not a straight/left/right/
     * generic fallback guess) and recently enough to plausibly be for the
     * same maneuver the notification listener is looking at right now.
     * Used to teach IconLearner's angle map — see its class doc.
     */
    fun confidentAngleIfFresh(withinMs: Long = 2000L): Int? {
        if (!accessAngleConfident) return null
        if (System.currentTimeMillis() - accessAngleConfidentAt > withinMs) return null
        return accessAngle
    }

    // Coalesce updates landing within COALESCE_MS of each other into one
    // outgoing packet, instead of sending once per source and letting the
    // second source's send immediately clobber the first's.
    private fun scheduleSend() {
        pendingSend?.let { handler.removeCallbacks(it) }
        val r = Runnable { resolveAndSend() }
        pendingSend = r
        handler.postDelayed(r, COALESCE_MS)
    }

    private fun resolveAndSend() {
        val turn = iconTurn ?: accessTurn ?: notifTurn ?: 0
        val turnSource = when {
            iconTurn != null -> "icon"
            accessTurn != null -> "accessibility-text"
            notifTurn != null -> "notification-text"
            else -> "default"
        }

        // Distance: prefer the notification's number. Google Maps rounds the
        // distance it displays on-screen (e.g. "300 m"), but the
        // accessibility reader exposes the exact unrounded figure (e.g.
        // "321 m") meant for screen readers — same underlying distance, two
        // different roundings. Preferring notification's here makes the
        // ESP32 match what you actually see on your phone screen.
        val distToTurn = when {
            notifDistToTurn > 0 -> notifDistToTurn
            accessDistToTurn > 0 -> accessDistToTurn
            else -> 0
        }

        // Prefer whichever source updated most recently for instruction
        // text, since a stale field from a source that hasn't fired in a
        // while is worse than a slightly-less-detailed fresh one.
        val useAccess = accessAt >= notifAt
        val instruction = if (useAccess && accessInstruction.isNotBlank()) {
            accessInstruction
        } else {
            notifInstruction.ifBlank { accessInstruction }
        }

        // Speed: prefer GPS (accurate, always available once permission is
        // granted, doesn't depend on Maps ever printing a "XX km/h" string)
        // over the text-scraped accessibility speed, falling back to the
        // latter only when no fresh GPS fix exists (e.g. permission denied,
        // just lost signal in a tunnel).
        val speedKmh = GpsSpeedProvider.currentSpeedKmhOrNull() ?: accessSpeedKmh

        // Angle: same trust priority as the turn code itself — an
        // icon-learned angle came from teaching this exact icon bitmap its
        // angle at a moment the text-exit-number match was confident, so
        // it's immune to a "2nd exit" phrase not appearing in the current
        // event's text. Falls back to whatever accessibility computed this
        // pass (confident or not) if the icon hasn't taught us this one yet.
        val angleDeg = iconAngle ?: accessAngle

        val packet = buildPacket(
            turn, distToTurn, accessTotalDist, accessRemainDist,
            speedKmh, angleDeg, instruction
        )

        val signature = "$turn|$distToTurn|$accessRemainDist|$speedKmh|$angleDeg|$instruction"
        lastPacket = packet // heartbeat resends this even if signature is unchanged
        if (signature == lastSentSignature) return
        lastSentSignature = signature

        NavLog.post(
            "MERGED -> turn=$turn (via $turnSource) dist=${distToTurn}m " +
                "speed=${speedKmh}kmh (gps=${GpsSpeedProvider.currentSpeedKmhOrNull()}) " +
                "angle=${angleDeg}deg (icon=${iconAngle}) remain=${accessRemainDist}m instr='$instruction'"
        )
        BleNavClient.sendNavBytes(packet)
    }

    private fun buildPacket(
        turn: Int,
        distToTurn: Int,
        totalDist: Int,
        remainDist: Int,
        speedKmh: Int,
        roundaboutAngleDeg: Int,
        instruction: String
    ): ByteArray {
        // ASCII-only, ESP32 font/display constraint — see main.cpp.
        var textBytes = instruction.filter { it.code in 0x20..0x7E }.toByteArray(Charsets.US_ASCII)
        if (textBytes.size > 60) textBytes = textBytes.copyOf(60)

        val buf = ByteArray(9 + textBytes.size)
        buf[0] = turn.toByte()
        buf[1] = ((distToTurn shr 8) and 0xFF).toByte()
        buf[2] = (distToTurn and 0xFF).toByte()
        buf[3] = ((totalDist shr 8) and 0xFF).toByte()
        buf[4] = (totalDist and 0xFF).toByte()
        buf[5] = ((remainDist shr 8) and 0xFF).toByte()
        buf[6] = (remainDist and 0xFF).toByte()
        buf[7] = speedKmh.coerceIn(0, 255).toByte()
        buf[8] = ((roundaboutAngleDeg.coerceIn(0, 360) * 255) / 360).toByte()
        textBytes.copyInto(buf, 9)
        return buf
    }

    /** Called when navigation ends so the next ride starts clean. */
    fun reset() {
        iconTurn = null
        iconAngle = null
        accessTurn = null
        notifTurn = null
        lastSentSignature = null
        lastPacket = null
    }
}
