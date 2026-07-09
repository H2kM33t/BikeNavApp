package com.bikenav.navlistenertest

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

object NavLog {
    /** Controlled by the "Show debug logs" toggle in Settings (see Prefs.showLogs). */
    var enabled: Boolean = false

    private val listeners = mutableListOf<(String) -> Unit>()
    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
    fun post(line: String) {
        if (!enabled) return
        Log.d("NavTest", line)
        listeners.forEach { it(line) }
    }
}

/**
 * Maneuver types inferred from the instruction text Google Maps puts in the
 * notification title. This is a superset of the firmware's TurnCode enum
 * (main.cpp now understands 0-15, matching TurnShowcase); toFirmwareTurnCode()
 * below maps every category down to a supported code.
 */
enum class ManeuverType {
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    FORK_LEFT,
    FORK_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    ROUNDABOUT_LEFT,
    ROUNDABOUT_RIGHT,
    EXIT_ROUNDABOUT_LEFT,
    EXIT_ROUNDABOUT_RIGHT,
    MERGE,
    RAMP_LEFT,
    RAMP_RIGHT,
    TAKE_EXIT,
    FERRY,
    HEAD_TOWARD,
    CONTINUE_STRAIGHT,
    REROUTING,
    ARRIVE,
    START_NAVIGATION,
    UNKNOWN;

    /**
     * Maps down to the firmware's TurnCode enum (main.cpp / TurnShowcase),
     * which supports codes 0-15:
     *   0=STRAIGHT 1=LEFT 2=RIGHT 3=SLIGHT_LEFT 4=SLIGHT_RIGHT
     *   5=SHARP_LEFT 6=SHARP_RIGHT 7=UTURN_LEFT 8=UTURN_RIGHT
     *   9=ROUNDABOUT_LEFT 10=ROUNDABOUT_RIGHT 11=MERGE
     *   12=FORK_LEFT 13=FORK_RIGHT 14=RAMP_LEFT 15=RAMP_RIGHT
     */
    fun toFirmwareTurnCode(): Int = when (this) {
        TURN_LEFT -> 1
        TURN_RIGHT -> 2
        SLIGHT_LEFT, KEEP_LEFT -> 3
        SLIGHT_RIGHT, KEEP_RIGHT -> 4
        SHARP_LEFT -> 5
        SHARP_RIGHT -> 6
        U_TURN_LEFT -> 7
        U_TURN_RIGHT -> 8
        ROUNDABOUT_LEFT, EXIT_ROUNDABOUT_LEFT -> 9
        ROUNDABOUT_RIGHT, EXIT_ROUNDABOUT_RIGHT -> 10
        MERGE -> 11
        FORK_LEFT -> 12
        FORK_RIGHT -> 13
        RAMP_LEFT -> 14
        RAMP_RIGHT -> 15
        // Everything else (take exit, ferry, head-toward, continue,
        // arrive, start, unknown) has no dedicated firmware icon yet, so it
        // falls back to the straight-ahead arrow rather than being dropped.
        else -> 0
    }
}

class NavNotificationListener : NotificationListenerService() {

    // Matches "1st exit", "2nd exit", "3rd exit", "4th exit" etc. - how Google
    // Maps actually phrases taking an exit off a roundabout, e.g.
    // "At the roundabout, take the 2nd exit onto Station Road".
    private val exitOrdinalRegex = Regex(
        "\\b(\\d+)(?:st|nd|rd|th)\\s+exit\\b",
        RegexOption.IGNORE_CASE
    )

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClearRunnable: Runnable? = null
    private var lastSentSignature: String? = null

    // How long to wait after a notification disappears before treating it as
    // a real end-of-navigation, since Maps sometimes removes and immediately
    // reposts a notification while it's updating internally.
    private val REMOVAL_DEBOUNCE_MS = 2500L

    // Deep inside a gated society / apartment complex, GPS multipath off
    // buildings makes Maps' position jump around right when you're closest
    // to the destination, which makes it post/remove/repost the nav
    // notification far more erratically than out on the open road. With
    // only REMOVAL_DEBOUNCE_MS of patience, that flapping was reading as
    // repeated "nav ended" events and blanking the display to "No route
    // data" right before arrival — the worst possible moment for it to go
    // dark. When we know from the last accessibility read that the route's
    // final stretch was already short, wait much longer before believing
    // the removal is real, since a false "ended" here is far more likely
    // than out on a long rural leg.
    private val REMOVAL_DEBOUNCE_NEAR_ARRIVAL_MS = 9000L
    private val NEAR_ARRIVAL_THRESHOLD_METRES = 200

    override fun onListenerConnected() {
        NavLog.post("=== Listener connected ===")
        IconLearner.init(applicationContext)
    }

    // Matches the leading "<number> <unit>" chunk Google Maps prefixes onto
    // the title, e.g. "100 m · Turn right onto..." or "1.2 km · Turn left...".
    // The separator between distance and instruction varies (·, -, or just
    // whitespace), so we only anchor on the distance+unit part itself.
    private val leadingDistanceRegex = Regex(
        """^\s*(\d+(?:\.\d+)?)\s*(m|km|ft|mi)\b[^A-Za-z]*""",
        RegexOption.IGNORE_CASE
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.maps") return

        pendingClearRunnable?.let { handler.removeCallbacks(it) }
        pendingClearRunnable = null

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        NavLog.post(
            "Parsed -> title='$title' text='$text' bigText='$bigText' subText='$subText'"
        )

        if (title.isBlank()) {
            NavLog.post("Skipped: blank title, keeping last known state")
            return
        }

        // Pull the leading "100 m · " / "1.2 km · " distance prefix out of
        // the title before classifying, so keyword matching runs on the
        // instruction alone, and so we can actually populate the packet's
        // distance field instead of leaving it at 0.
        val distanceMatch = leadingDistanceRegex.find(title)
        val distanceMetres = distanceMatch?.let { m ->
            val value = m.groupValues[1].toFloatOrNull() ?: 0f
            when (m.groupValues[2].lowercase()) {
                "km" -> (value * 1000f)
                "mi" -> (value * 1609.34f)
                "ft" -> (value * 0.3048f)
                else -> value // "m"
            }.toInt().coerceIn(0, 65535)
        } ?: 0

        val titleWithoutDistance = if (distanceMatch != null) {
            title.substring(distanceMatch.range.last + 1)
        } else {
            title
        }.trim().ifBlank { title } // never end up with an empty instruction

        val maneuver = classifyManeuver(titleWithoutDistance)
        if (maneuver == ManeuverType.REROUTING) {
            NavLog.post("Skipped: rerouting in progress, keeping last known state")
            return
        }

        val exitNumber = if (maneuver == ManeuverType.EXIT_ROUNDABOUT_LEFT ||
            maneuver == ManeuverType.EXIT_ROUNDABOUT_RIGHT
        ) {
            exitOrdinalRegex.find(titleWithoutDistance.lowercase())?.groupValues?.get(1)
        } else null

        // Fold the exit number into the instruction text itself, since the
        // firmware's packet has no separate field for it.
        val instructionRaw = if (exitNumber != null) {
            "$titleWithoutDistance (exit $exitNumber)"
        } else {
            titleWithoutDistance
        }

        // The ESP32's font is ASCII-only and the display splits lines by raw
        // byte offset. Any multi-byte UTF-8 character (·, °, accented
        // letters, etc.) either renders as garbage or gets sliced in the
        // middle, corrupting everything after it on that line. Strip
        // anything outside printable ASCII before it ever reaches the packet.
        val instruction = instructionRaw.filter { it.code in 0x20..0x7E }

        val signature = "${maneuver.name}|$distanceMetres|$instruction"
        if (signature == lastSentSignature) {
            NavLog.post("Skipped: identical to last sent state, not resending")
            return
        }
        lastSentSignature = signature

        // ---- Icon-based classification (see IconExtractor/IconHasher/IconLearner) ----
        // The instruction text is a guess about phrasing; the icon Maps
        // actually drew is ground truth for the maneuver. When the text
        // classifier is confident, teach the learner this icon's hash. On
        // every notification, resolve the icon hash and let NavDataState
        // prefer that resolution over both text-based reads.
        val iconBitmap = IconExtractor.extract(this, sbn)
        val iconHash = iconBitmap?.let { bmp ->
            val h = IconHasher.dHash(bmp)
            bmp.recycle()
            h
        }
        val textIsConfident = maneuver != ManeuverType.UNKNOWN
        if (iconHash != null && textIsConfident) {
            IconLearner.learn(iconHash, maneuver.toFirmwareTurnCode())
        }
        val iconResolvedTurn = iconHash?.let { IconLearner.lookup(it) }

        // Roundabout exit angle: teach this icon's hash its exact exit
        // angle whenever accessibility JUST confidently derived one (an
        // actual "2nd exit" match, not a generic straight/left/right
        // fallback guess) — see NavDataState.confidentAngleIfFresh(). Once
        // learned, this icon's angle is recognized even on a later pass
        // where the exit-number text isn't in view/isn't matched.
        val isRoundaboutIcon = maneuver == ManeuverType.ROUNDABOUT_LEFT ||
            maneuver == ManeuverType.ROUNDABOUT_RIGHT ||
            maneuver == ManeuverType.EXIT_ROUNDABOUT_LEFT ||
            maneuver == ManeuverType.EXIT_ROUNDABOUT_RIGHT
        if (iconHash != null && isRoundaboutIcon) {
            NavDataState.confidentAngleIfFresh()?.let { angle ->
                IconLearner.learnAngle(iconHash, angle)
            }
        }
        val iconResolvedAngle = iconHash?.let { IconLearner.lookupAngle(it) }
        NavDataState.updateIcon(iconResolvedTurn, iconResolvedAngle)

        val turnCode = if (textIsConfident) maneuver.toFirmwareTurnCode() else null
        NavLog.post(
            "Notification read -> textTurn=$turnCode iconTurn=$iconResolvedTurn iconAngle=$iconResolvedAngle " +
                "distance=${distanceMetres}m instruction='$instruction'"
        )
        NavDataState.updateFromNotification(turnCode, distanceMetres, instruction)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.maps") return

        val remainDist = NavDataState.lastKnownRemainDist()
        val debounceMs = if (remainDist in 1 until NEAR_ARRIVAL_THRESHOLD_METRES) {
            REMOVAL_DEBOUNCE_NEAR_ARRIVAL_MS
        } else {
            REMOVAL_DEBOUNCE_MS
        }
        NavLog.post("--- Maps notification removed (debouncing ${debounceMs}ms, lastRemainDist=${remainDist}m) ---")

        val runnable = Runnable {
            NavLog.post("--- Confirmed navigation ended ---")
            lastSentSignature = null
            NavDataState.reset()
            // No dedicated "cleared" packet in the firmware's format; the
            // display will show "No route data" on its own once STALE_TIMEOUT_MS
            // (8s) passes with no new packet, so nothing needs to be sent here.
        }
        pendingClearRunnable = runnable
        handler.postDelayed(runnable, debounceMs)
    }

    /**
     * Classifies the maneuver type from the instruction text using keyword
     * matching. Order matters - more specific phrases are checked before
     * generic ones (e.g. "sharp left" before "left").
     */
    private fun classifyManeuver(instruction: String): ManeuverType {
        val t = instruction.lowercase()

        return when {
            t.contains("rerouting") || t.contains("recalculating") -> ManeuverType.REROUTING
            t.contains("starting navigation") -> ManeuverType.START_NAVIGATION
            t.contains("you have arrived") || t.contains("arrive at") || t.startsWith("arrived") ->
                ManeuverType.ARRIVE

            t.contains("u-turn") || t.contains("u turn") ->
                if (t.contains("right")) ManeuverType.U_TURN_RIGHT else ManeuverType.U_TURN_LEFT

            t.contains("sharp left") -> ManeuverType.SHARP_LEFT
            t.contains("sharp right") -> ManeuverType.SHARP_RIGHT

            t.contains("slight left") -> ManeuverType.SLIGHT_LEFT
            t.contains("slight right") -> ManeuverType.SLIGHT_RIGHT

            t.contains("fork left") -> ManeuverType.FORK_LEFT
            t.contains("fork right") -> ManeuverType.FORK_RIGHT

            t.contains("keep left") -> ManeuverType.KEEP_LEFT
            t.contains("keep right") -> ManeuverType.KEEP_RIGHT

            // Directional roundabout exit: Maps doesn't say left/right
            // explicitly for the exit itself, so infer from whether the exit
            // number is on the smaller (left/counterclockwise) or larger
            // (right/clockwise) half; default to left if we can't tell.
            exitOrdinalRegex.containsMatchIn(t) ->
                if (t.contains("right")) ManeuverType.EXIT_ROUNDABOUT_RIGHT
                else ManeuverType.EXIT_ROUNDABOUT_LEFT
            t.contains("exit the roundabout") || t.contains("exit roundabout") ->
                if (t.contains("right")) ManeuverType.EXIT_ROUNDABOUT_RIGHT
                else ManeuverType.EXIT_ROUNDABOUT_LEFT
            t.contains("roundabout") || t.contains("rotary") ->
                if (t.contains("right")) ManeuverType.ROUNDABOUT_RIGHT
                else ManeuverType.ROUNDABOUT_LEFT

            t.contains("take the ferry") || t.contains("ferry") -> ManeuverType.FERRY

            t.contains("take the exit") || t.contains("take exit") -> ManeuverType.TAKE_EXIT
            t.contains("take the ramp") || t.contains("ramp") ->
                if (t.contains("right")) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT
            t.contains("merge") -> ManeuverType.MERGE

            t.contains("head toward") || t.contains("head north") ||
                    t.contains("head south") || t.contains("head east") ||
                    t.contains("head west") || t.contains("head northeast") ||
                    t.contains("head northwest") || t.contains("head southeast") ||
                    t.contains("head southwest") -> ManeuverType.HEAD_TOWARD

            t.contains("turn left") -> ManeuverType.TURN_LEFT
            t.contains("turn right") -> ManeuverType.TURN_RIGHT

            t.contains("continue") || t.contains("straight") -> ManeuverType.CONTINUE_STRAIGHT

            else -> ManeuverType.UNKNOWN
        }
    }
}
