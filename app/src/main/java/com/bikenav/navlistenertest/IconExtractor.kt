package com.bikenav.navlistenertest

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Pulls the turn-by-turn icon bitmap Google Maps actually rendered into its
 * navigation notification, instead of inferring the maneuver from the
 * instruction string. Two strategies, tried in order:
 *
 *  1. Inflate the notification's own RemoteViews (bigContentView, then
 *     contentView) and walk the resulting view tree for the largest
 *     ImageView - that's reliably the TBT icon, since every other image in
 *     the layout (app icon, etc.) is smaller.
 *  2. Fall back to Notification.getLargeIcon() if RemoteViews inflation
 *     fails (theme/OEM notification layouts vary).
 */
object IconExtractor {

    fun extract(context: Context, sbn: StatusBarNotification): Bitmap? {
        val notification = sbn.notification
        try {
            val views = notification.bigContentView ?: notification.contentView
            if (views != null) {
                val root = views.apply(context, FrameLayout(context))
                val iconView = findLargestImageView(root)
                val drawable = iconView?.drawable
                if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    return drawableToBitmap(drawable)
                }
            }
        } catch (e: Exception) {
            NavLog.post("Icon extract: RemoteViews inflate failed (${e.message}), falling back to large icon")
        }

        return try {
            val icon = notification.getLargeIcon() ?: return null
            val drawable = icon.loadDrawable(context) ?: return null
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            NavLog.post("Icon extract: large icon fallback failed (${e.message})")
            null
        }
    }

    private fun findLargestImageView(view: View?): ImageView? {
        if (view == null) return null
        var best: ImageView? = null
        var bestArea = 0
        fun visit(v: View) {
            if (v is ImageView) {
                val d = v.drawable
                val area = (d?.intrinsicWidth ?: 0) * (d?.intrinsicHeight ?: 0)
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
        }
        visit(view)
        return best
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }
}
