package com.bikenav.navlistenertest

/**
 * Shared exit-number -> angle -> left/right geometry, used by BOTH text
 * classifiers (NavAccessibilityService and NavNotificationListener) so a
 * roundabout is never called "left" by one source and "right" by the other
 * for the very same maneuver.
 *
 * Previously each source independently decided ROUNDABOUT_LEFT vs
 * ROUNDABOUT_RIGHT by checking whether the literal word "right" appeared
 * anywhere in Maps' instruction text. But Maps virtually never says
 * "left"/"right" for a roundabout exit - it says "take the 2nd exit onto
 * Station Road" - so that check almost always fell through to the LEFT
 * default no matter which way the exit actually pointed. That's exactly
 * why turnarounds "worked" on some circles (the rare ones where the
 * on-screen text happened to also contain the word "right") and silently
 * showed the wrong side on every other one - the icon-rotation angle was
 * being computed correctly the whole time, it just wasn't the thing
 * deciding left-vs-right. Deriving direction from that same angle fixes
 * every circle at once, instead of one at a time as reports come in.
 */
object RoundaboutGeometry {

    /**
     * India (and other left-hand-traffic countries) circulate roundabouts
     * CLOCKWISE. You enter heading "north" (0deg/straight in the firmware's
     * compass convention - see main.cpp's drawTurnIcon, which measures the
     * exit angle clockwise from north with 0deg = straight ahead), from the
     * "south" (180deg) arm. Walking the arms clockwise from there:
     *   1st exit ~225deg (sharp left)   5th exit ~45deg  (slight right)
     *   2nd exit ~270deg (left)         6th exit ~90deg  (right)
     *   3rd exit ~315deg (slight left)  7th exit ~135deg (sharp right)
     *   4th exit ~0deg   (STRAIGHT)     8th exit ~180deg (back where you
     *                                              came from - a near-full
     *                                              loop, geometrically the
     *                                              same maneuver as a
     *                                              U-turn)
     *
     * NOTE: an earlier version of this formula was (360 - exitNum*45) % 360,
     * which put the 4th exit (the single most common one - straight across)
     * at 180deg instead of 0deg - i.e. exactly the firmware's "looped back"
     * position - and put the 8th exit at 0deg ("straight") instead of
     * 180deg. That inversion is what made turnarounds "pass on some circles
     * and fail on others": whichever exit number you happened to be taking
     * decided whether the on-screen icon accidentally looked right or came
     * out rotated 180deg from reality, with the most common case (straight
     * across) being the most visibly broken one (its exit line landed
     * exactly on top of the entry line on the OLED).
     */
    fun angleForExit(exitNum: Int): Int = (180 + (exitNum * 45)) % 360

    /**
     * Sentinel for "no exit info yet" (e.g. Maps has only shown "At the
     * roundabout," with no exit number, and no directional keyword either -
     * genuinely nothing to go on). Every REAL angle this app ever produces
     * is a multiple of 45 (0/45/90/135/180/225/270/315 from angleForExit,
     * or 0/90/180/270 from the keyword fallback), so a small non-multiple
     * value like this can never collide with a real reading and is safe
     * for the firmware to detect and treat specially (see main.cpp's
     * drawTurnIcon: it skips drawing the exit line/arrowhead entirely for
     * this band, instead of drawing a fake exit line at some default
     * angle). Previously this case fell through to a plain 180deg default,
     * which - now that 180deg has a real meaning ("back where you came
     * from") - made "not sure yet" and "you're doing a near-full loop"
     * render as the exact same icon: a bare circle with no visible exit
     * arrow, since the exit line landed directly on top of the entry line.
     */
    const val ANGLE_UNKNOWN = 20

    /** True for the small band around ANGLE_UNKNOWN; see its doc. */
    fun isAngleUnknown(angleDeg: Int): Boolean {
        val a = ((angleDeg % 360) + 360) % 360
        return a in 1..30
    }

    /**
     * True for the right half of the circle (1..179deg, excluding the
     * ANGLE_UNKNOWN sentinel band so an unresolved angle is never
     * misclassified as a confident right turn). 0/180/181..359/unknown -
     * straight-back, straight-ahead, unknown, and the left half - read as
     * LEFT. This is the ONLY place left/right should be decided; text
     * keyword checks for roundabouts are unreliable and should not be used.
     */
    fun isRightSide(angleDeg: Int): Boolean {
        val a = ((angleDeg % 360) + 360) % 360
        return a in 1..179 && !isAngleUnknown(a)
    }
}
