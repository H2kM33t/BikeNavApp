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

            // header_text is "<duration> · <distance remaining> · <ETA>ETA"
            val etaDistanceText = headerText?.split("\u00B7")?.let { parts ->
                if (parts.size == 3) parts[1].trim() else null
            }

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
