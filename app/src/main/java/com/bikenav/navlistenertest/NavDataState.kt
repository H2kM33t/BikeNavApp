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
 *
 * The roundabout exit angle is a special case: it's measured directly from
 * the icon's own pixels every time (IconAngleAnalyzer), not learned or
 * hash-looked-up, so whenever an icon reading exists it's trusted outright.
 * The text-derived angle (accessAngle) only fills in before the first
 * roundabout notification of a ride has been analyzed.
 */
object NavDataState {
    private val handler = Handler(Looper.getMainLooper())
    private const val COALESCE_MS = 300L
    private const val HEARTBEAT_INTERVAL_MS = 5000L

    @Volatile private var iconTurn: Int? = null
    @Volatile private var iconAngle: Int? = null
    // Raw 32x32 1-bit XBM bitmap of the roundabout icon Maps actually drew
    // (see IconBitmapConverter), sent to the ESP32 instead of a computed
    // angle whenever we have one. Only ever non-null for roundabout icons.
    @Volatile private var iconBitmapBytes: ByteArray? = null

    @Volatile private var gpsSpeedKmh: Int? = null

    @Volatile private var accessTurn: Int? = null
    @Volatile private var accessInstruction: String = ""
    @Volatile private var accessDistToTurn: Int = 0
    @Volatile private var accessTotalDist: Int = 0
    @Volatile private var accessRemainDist: Int = 0
    @Volatile private var accessSpeedKmh: Int = 0
    @Volatile private var accessAngle: Int = 0
    @Volatile private var accessAt: Long = 0L

    @Volatile private var notifTurn: Int? = null
    @Volatile private var notifInstruction: String = ""
    @Volatile private var notifDistToTurn: Int = 0
    // Total remaining trip distance parsed from the notification's
    // "header_text" view ("12 min · 3.4 mi · 2:45 PM ETA" -> middle field),
    // the same source maisonsmd's GMapsNotification uses for this. Backs up
    // accessRemainDist below for when the accessibility tree scrape comes
    // back empty (e.g. rural roads where that view doesn't always render).
    @Volatile private var notifRemainDist: Int = 0
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

    /**
     * Called by NavNotificationListener whenever it resolves an icon hash.
     * bitmapBytes is the actual icon pixels (IconBitmapConverter output),
     * non-null only when this update came from a roundabout icon - it's
     * what actually gets drawn on the ESP32 now, with learnedAngle kept
     * only as a fallback for the one frame before a bitmap has arrived.
     */
    fun updateIcon(learnedTurn: Int?, learnedAngle: Int?, bitmapBytes: ByteArray? = null) {
        iconTurn = learnedTurn
        iconAngle = learnedAngle
        iconBitmapBytes = bitmapBytes
        scheduleSend()
    }

    /**
     * Called by NavNotificationListener with its own text-based read.
     * turnCode is null when the text classifier was UNKNOWN/ambiguous, so it
     * doesn't outrank a real accessibility or icon result. remainDist is
     * parsed from the notification's "header_text" ETA line (0 if
     * unavailable) and only used as a fallback - see notifRemainDist doc.
     */
    fun updateFromNotification(turnCode: Int?, distToTurn: Int, instruction: String, remainDist: Int = 0) {
        notifTurn = turnCode
        notifDistToTurn = distToTurn
        notifInstruction = instruction
        notifRemainDist = remainDist
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
        accessInstruction = instruction
        accessAt = System.currentTimeMillis()
        scheduleSend()
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

        // Total distance: accessTotalDist is sourced from the same
        // accessibility regex as accessRemainDist (Maps' notification
        // doesn't expose a separate "distance since start" figure, so both
        // legitimately track trip-remaining-distance) and can likewise sit
        // at 0 if that regex doesn't match this Maps version's phrasing.
        // Same header_text-based fallback as remainDist below.
        val totalDist = if (accessTotalDist > 0) accessTotalDist else notifRemainDist

        // Remaining trip distance: accessibility's tree-scraped figure is
        // preferred (unrounded, precise), but on rural roads that view
        // sometimes doesn't render at all and accessRemainDist sits at 0.
        // Fall back to the notification's "header_text" ETA line reading
        // in that case, same source Mason's app uses for this figure.
        val remainDist = if (accessRemainDist > 0) accessRemainDist else notifRemainDist

        // Attach the actual icon pixels whenever we have them, for any
        // turn type - not just roundabouts. The ESP32 now prefers this
        // bitmap over its own vector icon for every turn, falling back to
        // the vector icon only when no bitmap is available yet.
        val bitmapToSend = iconBitmapBytes

        val packet = buildPacket(
            turn, distToTurn, totalDist, remainDist,
            speedKmh, angleDeg, instruction, bitmapToSend
        )

        val signature = "$turn|$distToTurn|$totalDist|$remainDist|$speedKmh|$angleDeg|$instruction|" +
            "${bitmapToSend?.contentHashCode()}"
        lastPacket = packet // heartbeat resends this even if signature is unchanged
        if (signature == lastSentSignature) return
        lastSentSignature = signature

        NavLog.post(
            "MERGED -> turn=$turn (via $turnSource) dist=${distToTurn}m " +
                "speed=${speedKmh}kmh (gps=${GpsSpeedProvider.currentSpeedKmhOrNull()}) " +
                "angle=${angleDeg}deg icon=${if (bitmapToSend != null) "bitmap(${bitmapToSend.size}B)" else "none"} " +
                "remain=${remainDist}m (access=${accessRemainDist}m notif=${notifRemainDist}m) instr='$instruction'"
        )
        BleNavClient.sendNavBytes(packet)
    }

    // Packet layout (v2 - see main.cpp onNavPacket for the matching parser):
    //   byte 0   : turn code
    //   byte 1-2 : distance to next turn, uint16 BE, metres
    //   byte 3-4 : total journey distance, uint16 BE, metres
    //   byte 5-6 : remaining journey distance, uint16 BE, metres
    //   byte 7   : speed km/h
    //   byte 8   : roundabout angle, 0-360deg mapped to 0-255 (fallback only,
    //              used for the one frame before a bitmap has arrived)
    //   byte 9   : iconFlag - 1 if a 32x32 1bpp bitmap follows, else 0
    //   [byte 10..137: 128-byte packed XBM bitmap, only if iconFlag==1]
    //   remaining bytes: ASCII instruction text
    private fun buildPacket(
        turn: Int,
        distToTurn: Int,
        totalDist: Int,
        remainDist: Int,
        speedKmh: Int,
        roundaboutAngleDeg: Int,
        instruction: String,
        iconBitmap: ByteArray?
    ): ByteArray {
        // ASCII-only, ESP32 font/display constraint — see main.cpp.
        var textBytes = instruction.filter { it.code in 0x20..0x7E }.toByteArray(Charsets.US_ASCII)
        if (textBytes.size > 60) textBytes = textBytes.copyOf(60)

        val hasIcon = iconBitmap != null && iconBitmap.size == IconBitmapConverter.PACKED_SIZE
        val headerSize = 10 // bytes 0..9
        val iconSize = if (hasIcon) IconBitmapConverter.PACKED_SIZE else 0

        val buf = ByteArray(headerSize + iconSize + textBytes.size)
        buf[0] = turn.toByte()
        buf[1] = ((distToTurn shr 8) and 0xFF).toByte()
        buf[2] = (distToTurn and 0xFF).toByte()
        buf[3] = ((totalDist shr 8) and 0xFF).toByte()
        buf[4] = (totalDist and 0xFF).toByte()
        buf[5] = ((remainDist shr 8) and 0xFF).toByte()
        buf[6] = (remainDist and 0xFF).toByte()
        buf[7] = speedKmh.coerceIn(0, 255).toByte()
        buf[8] = ((roundaboutAngleDeg.coerceIn(0, 360) * 255) / 360).toByte()
        buf[9] = if (hasIcon) 1 else 0

        var offset = headerSize
        if (hasIcon) {
            iconBitmap!!.copyInto(buf, offset)
            offset += iconSize
        }
        textBytes.copyInto(buf, offset)
        return buf
    }

    /** Called when navigation ends so the next ride starts clean. */
    fun reset() {
        iconTurn = null
        iconAngle = null
        iconBitmapBytes = null
        accessTurn = null
        notifTurn = null
        notifRemainDist = 0
        lastSentSignature = null
        lastPacket = null
        // Without this, BleNavClient's own cached copy (kept separately so
        // it can replay on a mid-ride BLE reconnect) survives navigation
        // ending entirely - so disconnecting and reconnecting AFTER closing
        // Maps would still replay the last real turn/distance as if nav
        // were still active. This makes "nav genuinely ended" also mean
        // "nothing stale left to replay."
        BleNavClient.clearLastKnownNav()
    }
}
