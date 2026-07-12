package com.bikenav.navlistenertest

import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Reads distance/instruction/ETA straight out of the notification's own
 * inflated RemoteViews layout, by walking to specific named child views -
 * "title" (distance), "text" (road name + description), "header_text"
 * (ETE/distance-remaining/ETA line) - the same approach maisonsmd's
 * esp32-google-maps GMapsNotification.parseRemoteView() uses.
 *
 * This exists because EXTRA_TITLE (what NavNotificationListener used to
 * parse distance out of) is a flattened fallback string Android generates
 * for accessibility/lockscreen use, and Maps doesn't always populate it
 * the same way it populates the actual rendered "title" view - on long
 * rural stretches where the next turn is far off, EXTRA_TITLE sometimes
 * carries no distance number at all even though the on-screen notification
 * clearly shows one. Reading the real view sidesteps that entirely: it's
 * literally what's drawn on screen, so it can't be missing what you can see.
 */
object GMapsRemoteViewParser {

    // Matches a bare "<number> <unit>" value with nothing else meaningful
    // around it - e.g. "3.4 mi" or "500 m" - used to pick the distance part
    // out of header_text without caring which position it's in. Deliberately
    // requires a word boundary after the unit so "10 min" (duration) never
    // matches on the "m" in "min".
    private val standaloneDistanceRegex = Regex(
        """^\s*\d+\.?\d*\s*(?:km|mi|m|ft)\b\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val looseDistanceRegex = Regex(
        """\d+\.?\d*\s*(?:km|mi|m|ft)\b""",
        RegexOption.IGNORE_CASE
    )

    data class ParsedFields(
        val titleText: String?,     // "title" view - distance to next turn alone, e.g. "500 ft"
        val directionText: String?, // "text" view - road name + description
        val etaDistanceText: String? // middle field of "header_text", e.g. total distance remaining
    )

    fun parse(context: Context, sbn: StatusBarNotification): ParsedFields? {
        return try {
            val notification = sbn.notification
            val views = notification.bigContentView ?: notification.contentView ?: return null
            val root = views.apply(context, FrameLayout(context)) as? ViewGroup ?: return null

            val titleText = (findChildByEntryName(context, root, "title") as? TextView)
                ?.text?.toString()?.trim()
            val directionText = (findChildByEntryName(context, root, "text") as? TextView)
                ?.text?.toString()?.trim()
            val headerText = (findChildByEntryName(context, root, "header_text") as? TextView)
                ?.text?.toString()

            // header_text is normally "<duration> · <distance remaining> ·
            // <ETA>ETA" (3 parts), but requiring exactly 3 parts turned out
            // to be too strict - on rural stretches (GPS reacquisition,
            // long stretches with no imminent turn, etc.) Maps can render
            // this with 2 parts (e.g. no ETA clock time shown) or otherwise
            // reshuffled, and the old code just gave up entirely whenever
            // the count wasn't exactly 3. Instead, scan whatever parts ARE
            // present and take whichever one actually looks like a
			// standalone distance value ("3.4 mi", not "12 min" or
            // "2:45 PM") - order/count no longer matters.
            val etaDistanceText = headerText?.split("\u00B7")
                ?.map { it.trim() }
                ?.firstOrNull { part ->
                    standaloneDistanceRegex.matches(part) ||
                        (looseDistanceRegex.containsMatchIn(part) && ":" !in part)
                }
                // Last resort: header_text had no "·" separators at all (a
                // degraded/rural rendering state) - just grab any distance
                // number anywhere in the raw string.
                ?: headerText?.let { looseDistanceRegex.find(it)?.value }

            ParsedFields(
                titleText = titleText?.ifBlank { null },
                directionText = directionText?.ifBlank { null },
                etaDistanceText = etaDistanceText
            )
        } catch (e: Exception) {
            NavLog.post("RemoteView field parse failed (${e.message}), falling back to EXTRA_TITLE")
            null
        }
    }

    private fun getEntryName(context: Context, view: View): String {
        return try {
            if (view.id > 0) context.resources.getResourceEntryName(view.id) else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun findChildByEntryName(context: Context, group: ViewGroup, name: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (getEntryName(context, child) == name) return child
            if (child is ViewGroup) {
                findChildByEntryName(context, child, name)?.let { return it }
            }
        }
        return null
    }
}
