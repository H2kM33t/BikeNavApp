package com.bikenav.navlistenertest

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NavAccessibilityService : AccessibilityService() {

    // Your Maps is showing imperial units ("100 ft", "0.1 mi" — visible in
    // the RAW log), not metric. The old regex only matched m/km, so it
    // never matched at all here, leaving distance stuck at 0 and the
    // "in 100 ft" suffix never stripped from the instruction text.
    private val distanceRegex = Regex("""in (\d+\.?\d*)\s*(m|km|ft|mi)\b""", RegexOption.IGNORE_CASE)
    private val speedRegex = Regex("""(\d+)\s*(?:kilometres per hour|km/h|kmh|mph)""")
    // Broadened: the old regex only matched the literal phrase
    // "Distance remaining is X km", which Maps' real accessibility text
    // very likely never uses verbatim — this is almost certainly why
    // remaining distance was always 0. Now matches "X km left",
    // "X km remaining", or a bare "X km, Y min" ETA string — and also
    // imperial units.
    private val remainingRegex = Regex("""(\d+\.?\d*)\s*(km|m|mi|ft)\s*(?:left|remaining|,)""", RegexOption.IGNORE_CASE)

    // Converts a parsed (value, unit) pair to metres. Centralizing this
    // means m/km/ft/mi are all handled consistently everywhere distance
    // is computed, instead of each call site having its own m/km-only
    // ternary that silently mis-handles ft/mi.
    private fun toMetres(value: Float, unit: String): Int {
        return when (unit.lowercase()) {
            "km" -> (value * 1000f).toInt()
            "mi" -> (value * 1609.34f).toInt()
            "ft" -> (value * 0.3048f).toInt()
            else -> value.toInt() // "m"
        }
    }

    private val instructionSuffixRegex = Regex("""\s+in \d+\.?\d*\s*(m|km|ft|mi)$""", RegexOption.IGNORE_CASE)
    private var lastSent = ""
    private var lastPayload: ByteArray? = null
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val HEARTBEAT_INTERVAL_MS = 5000L  // resend even if unchanged, still well under ESP32's 8s stale timeout

    // Google Maps fires accessibility events very frequently while navigating.
    // Walking the full node tree on every single one burns CPU (and battery)
    // for no benefit, since instruction text changes at most once every
    // couple of seconds. Skip events that arrive too soon after the last
    // processed one.
    private val EVENT_THROTTLE_MS = 600L
    private var lastEventProcessedAt = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            lastPayload?.let { BleNavClient.sendNavBytes(it) }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "com.google.android.apps.maps") return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEventProcessedAt < EVENT_THROTTLE_MS) return
        lastEventProcessedAt = now

        // rootInActiveWindow only returns nodes for the currently ACTIVE/
        // focused window. When Maps is in picture-in-picture or minimized,
        // its window is still emitting these events but is no longer
        // "active", so rootInActiveWindow comes back null (or points at
        // whatever window IS active, e.g. the launcher) and everything below
        // silently no-ops. Walking up from the event's own source node finds
        // Maps' window root regardless of focus state.
        val root = event.source?.let { source ->
            var top = source
            while (true) {
                top = top.parent ?: break
            }
            top
        } ?: rootInActiveWindow ?: return

        val allText = mutableListOf<String>()
        collectText(root, allText)
        if (allText.isEmpty()) return

        // Find the main instruction node — contains distance + turn.
        // Google Maps can render more than one matching node at once (e.g. a
        // preview of the next step), so pick whichever has the SMALLEST
        // distance — that's always the current maneuver, never a lookahead.
        //
        // EXCEPT: Maps prefixes genuine lookahead previews with "Then " (e.g.
        // "Then turn right onto Oak St in 300 ft"), and that preview's
        // printed distance is often SMALLER than the real current
        // instruction's distance. Left unfiltered, the min-distance pick
        // above grabs the "Then ..." line and the ESP32 shows the upcoming
        // turn early instead of the current one (e.g. "Head west" ->
        // shows "turn right" immediately). Drop any "Then "-prefixed
        // candidate before picking the minimum.
        // Debug aid: log everything Maps exposed this pass, so you can see
        // exactly what text/phrasing is available (e.g. to tune the speed
        // and remaining-distance regexes above against your actual device).
        NavLog.post("RAW: " + allText.joinToString(" | "))

        // Only treat a node as a real turn instruction if it both matches
        // the distance pattern AND contains an actual maneuver verb. Without
        // this, a stale/off-screen node (e.g. a collapsed steps list still
        // holding an already-completed turn) can win the "smallest distance"
        // comparison below and get shown even though you've already passed
        // it — this is almost certainly why old turns kept reappearing.
        val turnVerbs = listOf(
            "turn", "continue", "head", "keep", "take", "merge", "u-turn",
            "uturn", "roundabout", "exit", "straight", "arrive"
        )
        val candidates = allText
            .filter { distanceRegex.containsMatchIn(it) }
            .filterNot { it.trim().startsWith("then ", ignoreCase = true) }
            .filter { text -> turnVerbs.any { text.contains(it, ignoreCase = true) } }
        val instructionNode = candidates.minByOrNull { candidate ->
            val m = distanceRegex.find(candidate)
            val value = m?.groupValues?.get(1)?.toFloatOrNull()
            val unit = m?.groupValues?.get(2) ?: "m"
            if (value == null) Float.MAX_VALUE else toMetres(value, unit).toFloat()
        }

        // Find current speed
        val speedNode = allText.firstOrNull {
            speedRegex.containsMatchIn(it)
        }

        // Find remaining distance
        val remainingNode = allText.firstOrNull {
            remainingRegex.containsMatchIn(it)
        }

        if (instructionNode == null) return

        // Parse distance to next turn
        val distanceMatch = distanceRegex.find(instructionNode)
        val distanceValue = distanceMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val distanceUnit = distanceMatch?.groupValues?.get(2) ?: "m"
        val distanceMetres = toMetres(distanceValue, distanceUnit)

        // Parse turn direction
        val turn = parseTurnDirection(instructionNode)

        // Parse speed
        val speedMatch = speedNode?.let { speedRegex.find(it) }
        val speedKmh = speedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // Parse remaining distance
        val remainMatch = remainingNode?.let { remainingRegex.find(it) }
        val remainValue = remainMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val remainUnit = remainMatch?.groupValues?.get(2) ?: "m"
        val remainMetres = toMetres(remainValue, remainUnit)

        // Build instruction text — strip the distance part at the end
        val instruction = instructionNode
            .replace(instructionSuffixRegex, "")
            .trim()

        // Build payload matching navigation.h binary format:
        // byte 0   : TurnDir (0-10)
        // byte 1-2 : distance to next turn uint16 big-endian metres
        // byte 3-4 : total journey distance (use remaining as approximation)
        // byte 5-6 : remaining journey distance uint16 big-endian metres
        // byte 7   : speed in km/h (0-255)
        // byte 8+  : "streetName|towardName" ASCII
        val payload = buildBinaryPayload(
            turn, distanceMetres, remainMetres, remainMetres, speedKmh, instruction
        )

        val payloadStr = payload.joinToString("") { "%02X".format(it) }
        lastPayload = payload  // heartbeat timer will keep resending this even if events stop
        if (payloadStr == lastSent) return  // debounce — don't log/send twice for identical data
        lastSent = payloadStr

        NavLog.post("NAV -> turn=$turn dist=${distanceMetres}m speed=${speedKmh}kmh remain=${remainMetres}m instruction='$instruction'")
        BleNavClient.sendNavBytes(payload)
    }

    private fun parseTurnDirection(text: String): Int {
        val lower = text.lowercase()
        // Only look at the leading verb phrase (before "toward"/"onto"/street name)
        // so words like "Left" or "Wright" inside a street name never match.
        val leadPhrase = lower.substringBefore(" toward ")
            .substringBefore(" onto ")
            .trim()
        // Codes match the firmware's TurnCode enum (main.cpp / TurnShowcase, 0-15):
        //   0=STRAIGHT 1=LEFT 2=RIGHT 3=SLIGHT_LEFT 4=SLIGHT_RIGHT
        //   5=SHARP_LEFT 6=SHARP_RIGHT 7=UTURN_LEFT 8=UTURN_RIGHT
        //   9=ROUNDABOUT_LEFT 10=ROUNDABOUT_RIGHT 11=MERGE
        //   12=FORK_LEFT 13=FORK_RIGHT 14=RAMP_LEFT 15=RAMP_RIGHT
        return when {
            (leadPhrase.contains("u-turn") || leadPhrase.contains("uturn")) &&
                    leadPhrase.contains("right") -> 8
            leadPhrase.contains("u-turn") || leadPhrase.contains("uturn") -> 7
            leadPhrase.contains("sharp left") -> 5
            leadPhrase.contains("sharp right") -> 6
            leadPhrase.contains("slight left") -> 3
            leadPhrase.contains("slight right") -> 4
            leadPhrase.contains("fork left") -> 12
            leadPhrase.contains("fork right") -> 13
            leadPhrase.contains("ramp") && leadPhrase.contains("right") -> 15
            leadPhrase.contains("ramp") -> 14
            leadPhrase.contains("merge") -> 11
            leadPhrase.contains("turn left") || leadPhrase.startsWith("left") -> 1
            leadPhrase.contains("turn right") || leadPhrase.startsWith("right") -> 2
            leadPhrase.contains("roundabout") && leadPhrase.contains("right") -> 10
            leadPhrase.contains("roundabout") -> 9
            leadPhrase.contains("straight") || leadPhrase.contains("head") || leadPhrase.contains("continue") -> 0
            else -> 0
        }
    }

    private fun buildBinaryPayload(
        turn: Int,
        distMetres: Int,
        totalMetres: Int,
        remainMetres: Int,
        speedKmh: Int,
        instruction: String
    ): ByteArray {
        val textBytes = instruction.toByteArray(Charsets.UTF_8).take(60).toByteArray()
        val buf = ByteArray(8 + textBytes.size)
        buf[0] = turn.toByte()
        buf[1] = ((distMetres shr 8) and 0xFF).toByte()
        buf[2] = (distMetres and 0xFF).toByte()
        buf[3] = ((totalMetres shr 8) and 0xFF).toByte()
        buf[4] = (totalMetres and 0xFF).toByte()
        buf[5] = ((remainMetres shr 8) and 0xFF).toByte()
        buf[6] = (remainMetres and 0xFF).toByte()
        buf[7] = speedKmh.coerceIn(0, 255).toByte()
        textBytes.copyInto(buf, 8)
        return buf
    }

    private fun collectText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) list.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) list.add(desc)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), list)
        }
    }

    override fun onInterrupt() {}
}