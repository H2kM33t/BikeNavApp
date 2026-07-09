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
    // Matches Maps' "take the 2nd exit" / "3rd exit" style phrasing at
    // roundabouts — this is real data Maps already provides, just not
    // previously forwarded to the display.
    private val exitNumberRegex = Regex("""(\d+)(?:st|nd|rd|th)\s+exit""", RegexOption.IGNORE_CASE)

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

    // ---- Interlock against stale/glitchy single-frame reads ----
    // A stale/off-screen node can momentarily win candidate selection and
    // look like a legitimate turn change (this is what caused "shows a
    // turn I already passed"). A NEW instruction is only accepted
    // immediately if it matches what the PREVIOUS event's "Then ..."
    // preview predicted (i.e. it's corroborated in advance by Maps
    // itself — not extra data, just cross-checking what Maps already
    // showed). Otherwise it must repeat identically on two consecutive
    // events before being trusted. Adds at most one throttle cycle
    // (~600ms) of latency on a genuinely new turn.
    private var pendingThenTurn: Int? = null
    private var unconfirmedCore: String? = null
    private var lastAcceptedCore: String = ""

    // Google Maps fires accessibility events very frequently while navigating.
    // Walking the full node tree on every single one burns CPU (and battery)
    // for no benefit, since instruction text changes at most once every
    // couple of seconds. Skip events that arrive too soon after the last
    // processed one.
    private val EVENT_THROTTLE_MS = 600L
    private var lastEventProcessedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // NavDataState now owns the single shared heartbeat — previously this
        // service and NavNotificationListener both ran their own, resending
        // whichever payload each had last built independently.
        NavDataState.startHeartbeat()
        GpsSpeedProvider.start(this)
    }

    override fun onDestroy() {
        NavDataState.stopHeartbeat()
        GpsSpeedProvider.stop(this)
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

        val allNodes = mutableListOf<TextNode>()
        collectText(root, allNodes)
        if (allNodes.isEmpty()) return
        val allText = allNodes.map { it.text }

        // Find the main instruction node — contains distance + turn.
        // Google Maps can render more than one matching node at once (e.g. a
        // preview of the next step, or the expandable steps list further
        // down the route), so pick whichever is TOPMOST ON SCREEN — that's
        // reliably the live turn-by-turn banner. Distance is NOT a safe
        // signal here: each entry in the steps list carries its own
        // embedded distance (the length of THAT road segment, not distance
        // from your current position), so a short future segment several
        // turns down the route can have a smaller printed number than the
        // real current instruction and would win a smallest-distance pick.
        //
        // EXCEPT: Maps prefixes genuine lookahead previews with "Then " (e.g.
        // "Then turn right onto Oak St in 300 ft") and that preview can
        // render ABOVE the current instruction, so it's filtered out below
        // before the topmost pick, rather than being allowed to win on
        // position.
        // Debug aid: log everything Maps exposed this pass, so you can see
        // exactly what text/phrasing is available (e.g. to tune the speed
        // and remaining-distance regexes above against your actual device).
        NavLog.post("RAW: " + allText.joinToString(" | "))

        // Only treat a node as a real turn instruction if it both matches
        // the distance pattern AND contains an actual maneuver verb. Without
        // this, a stale/off-screen node (e.g. a collapsed steps list still
        // holding an already-completed turn) can win the topmost comparison
        // below and get shown even though you've already passed it — this
        // is almost certainly why old turns kept reappearing.
        val turnVerbs = listOf(
            "turn", "continue", "head", "keep", "take", "merge", "u-turn",
            "uturn", "roundabout", "exit", "straight", "arrive"
        )
        // Maps sometimes renders a SEPARATE landmark-narration node for the
        // same upcoming point — e.g. "At Baroda Dairy Circle, continue" —
        // alongside the real maneuver banner ("Slight right onto Kanaiyalal
        // Munshi Marg"). Both contain a turnVerb ("continue") and can land
        // at nearly the same screen position, so unfiltered they compete
        // and can flip-flop which one "wins" from event to event — that's
        // what looks like an old turn reappearing.
        // "At the roundabout"/"At the traffic circle" are legitimate real
        // instructions and stay allowed; only the "At <named place>,"
        // landmark-narration shape gets dropped.
        val landmarkNarrationRegex = Regex(
            """^at\s+(?!the\s+roundabout|the\s+traffic\s+circle)""",
            RegexOption.IGNORE_CASE
        )
        val candidatesRaw = allNodes
            .filter { distanceRegex.containsMatchIn(it.text) }
            .filterNot { it.text.trim().startsWith("then ", ignoreCase = true) }
            .filter { node -> turnVerbs.any { node.text.contains(it, ignoreCase = true) } }
        val nonLandmark = candidatesRaw.filterNot { landmarkNarrationRegex.containsMatchIn(it.text.trim()) }
        // Fall back to the unfiltered list only if landmark-filtering would
        // leave nothing at all — better a landmark line than no candidate.
        val candidates = if (nonLandmark.isNotEmpty()) nonLandmark else candidatesRaw
        val instructionNode = candidates.minByOrNull { it.top }?.text

        // Find current speed
        val speedNode = allText.firstOrNull {
            speedRegex.containsMatchIn(it)
        }

        // Find remaining distance
        val remainingNode = allText.firstOrNull {
            remainingRegex.containsMatchIn(it)
        }

        // "Then ..." preview, if present this pass — used by the interlock
        // below to corroborate the NEXT instruction change before trusting it.
        val thenNode = allText.firstOrNull { it.trim().startsWith("then ", ignoreCase = true) }

        if (instructionNode == null) return

        // Parse distance to next turn
        val distanceMatch = distanceRegex.find(instructionNode)
        val distanceValue = distanceMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val distanceUnit = distanceMatch?.groupValues?.get(2) ?: "m"
        val distanceMetres = toMetres(distanceValue, distanceUnit)

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
            // The roundabout icon on-device already conveys "you're at a
            // roundabout" — this generic prefix was eating both of the
            // OLED's 2 wrap lines, so the actual road name/direction
            // ("...take the 2nd exit onto Palace Rd") never made it onto
            // the screen at all. Stripping it here frees that space for
            // the part that's actually useful to glance at. Purely a
            // display-text change — does not affect angle/turn parsing,
            // which both read from instructionNode (above), not this.
            .replace(Regex("""^at the (roundabout|traffic circle),?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        // Roundabout exit angle: Google Maps' spoken/on-screen instruction
        // often literally says which exit to take (e.g. "take the 2nd
        // exit"), which is real information Maps is already giving you —
        // just not previously being forwarded to the display.
        //
        // Spaced in 45-degree increments (not 90) — matches how real
        // roundabout icons represent exits: the 1st exit is a fairly
        // sharp ~45deg turn, straight-ahead sits around the middle
        // (~180deg for a 4th exit), and later exits fan out toward
        // almost a full loop back. A uniform 90deg-per-exit assumption
        // was too coarse and could never produce 45/135/225/315deg at
        // all even though the drawing code itself supports any angle.
        //
        // Computed BEFORE the interlock gate (and folded into "core" below)
        // so a glitchy single-frame misread of the exit number — Maps'
        // node text for exit number can lag/blip independently of the
        // turn+instruction text — gets the same corroborate-or-repeat
        // protection as everything else, instead of bypassing the gate
        // entirely and flashing a wrong direction for one frame before
        // self-correcting.
        val exitNumberMatch = exitNumberRegex.find(instructionNode)
        val roundaboutAngleDeg: Int = when {
            exitNumberMatch != null -> {
                val exitNum = exitNumberMatch.groupValues[1].toIntOrNull() ?: 4
                // Delegates to RoundaboutGeometry so this can never drift out
                // of sync with NavNotificationListener's copy again - see its
                // doc for why the old separately-maintained formula here was
                // 180deg inverted from the firmware's compass convention.
                RoundaboutGeometry.angleForExit(exitNum)
            }
            instructionNode.contains("straight", ignoreCase = true) -> 0
            instructionNode.contains("left", ignoreCase = true) -> 270
            instructionNode.contains("right", ignoreCase = true) -> 90
            // Genuinely nothing to go on yet (e.g. Maps has only shown "At
            // the roundabout," with no exit number in view). Previously
            // this fell through to a plain 180 - which now legitimately
            // means "you're looping almost all the way back" - so "not
            // sure yet" and "you're doing a near-full loop" rendered as
            // the exact same (visually blank) icon. See
            // RoundaboutGeometry.ANGLE_UNKNOWN.
            else -> RoundaboutGeometry.ANGLE_UNKNOWN
        }

        // Parse turn direction. parseTurnDirection() returns a placeholder
        // 9 (ROUNDABOUT_LEFT) for ANY roundabout mention, since text alone
        // can't reliably tell left from right (see RoundaboutGeometry doc).
        // Now that roundaboutAngleDeg is known, resolve the real side from
        // the SAME angle math driving the icon's rotation, instead of
        // trusting whichever default parseTurnDirection guessed.
        val baseTurn = parseTurnDirection(instructionNode)
        val turn = if (baseTurn == 9 || baseTurn == 10) {
            if (RoundaboutGeometry.isRightSide(roundaboutAngleDeg)) 10 else 9
        } else {
            baseTurn
        }

        // ---- Interlock gate ----
        // "core" ignores distance (which naturally changes every event) and
        // tracks turn+instruction+roundaboutAngle — i.e. did the actual
        // maneuver (including which way a roundabout exit points) change.
        // Angle is included here so a single glitchy frame that misreads
        // the exit number gets deferred/corroborated exactly like a
        // glitchy turn or instruction would, instead of sailing through
        // unprotected and flashing the wrong direction for one frame.
        val core = "$turn|$instruction|$roundaboutAngleDeg"
        val thenTurn = thenNode?.let { parseTurnDirection(it) }

        if (core != lastAcceptedCore) {
            val corroborated = pendingThenTurn != null && pendingThenTurn == turn
            val repeated = unconfirmedCore == core
            if (!corroborated && !repeated) {
                // Not yet trusted — stash it and wait for confirmation next
                // pass instead of sending a possibly-stale/glitchy read.
                unconfirmedCore = core
                pendingThenTurn = thenTurn
                NavLog.post("Interlock: deferring uncorroborated change '$instruction' (turn=$turn, angle=$roundaboutAngleDeg) pending confirmation")
                return
            }
            // Either corroborated by the previous "Then" preview, or seen
            // twice in a row — accept it.
            lastAcceptedCore = core
            unconfirmedCore = null
        }
        pendingThenTurn = thenTurn

        NavLog.post("NAV -> turn=$turn dist=${distanceMetres}m speed=${speedKmh}kmh remain=${remainMetres}m instruction='$instruction'")

        // Publish to the shared merge point instead of sending BLE directly.
        // NavDataState decides whether this turn code, an icon-hash
        // resolution, or the notification listener's own text read wins —
        // see NavDataState's class doc for the priority order and why this
        // replaced two independently-racing senders.
        NavDataState.updateFromAccessibility(
            turn, distanceMetres, remainMetres, remainMetres, speedKmh,
            roundaboutAngleDeg, exitNumberMatch != null, instruction
        )
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
            // India (left-hand traffic): an unqualified "Make a U-turn"
            // sweeps from the left lane across to the right, so RIGHT is
            // the correct default whenever Maps doesn't say a side at all.
            // Only honor an explicit "left" if Maps actually says one.
            (leadPhrase.contains("u-turn") || leadPhrase.contains("uturn")) &&
                    leadPhrase.contains("left") -> 7
            leadPhrase.contains("u-turn") || leadPhrase.contains("uturn") -> 8
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
            // Placeholder only - text can't reliably tell left from right
            // for a roundabout exit. The caller overrides this with the
            // angle-derived side once roundaboutAngleDeg is known; see
            // RoundaboutGeometry.
            leadPhrase.contains("roundabout") -> 9
            leadPhrase.contains("straight") || leadPhrase.contains("head") || leadPhrase.contains("continue") -> 0
            else -> 0
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, list: MutableList<TextNode>) {
        if (node == null) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) list.add(TextNode(text, bounds.top))
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) list.add(TextNode(desc, bounds.top))
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), list)
        }
    }

    override fun onInterrupt() {}
}

// Text paired with its on-screen top-Y coordinate. Maps' live turn-by-turn
// banner always renders at the top of the screen; the expandable steps
// list (opened via "Activate to open step list", or sometimes present in
// the tree even collapsed) renders below it. Each step in that list carries
// its OWN embedded distance — the length of that road segment, not the
// distance from your current position — so picking "smallest distance"
// across all matching text can grab a short FUTURE segment several turns
// down the route instead of the real current instruction. Tracking
// position lets us prefer whatever's topmost on screen instead, which is
// reliably the live banner regardless of what distance numbers happen to
// appear elsewhere in the tree.
private data class TextNode(val text: String, val top: Int)