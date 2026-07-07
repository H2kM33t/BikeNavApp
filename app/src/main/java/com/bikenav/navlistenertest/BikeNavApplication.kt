package com.bikenav.navlistenertest

import android.app.Application
import com.google.android.material.color.DynamicColors

class BikeNavApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Applies Android 12+ Material You dynamic color (derived from the
        // user's wallpaper) to every Activity automatically. On devices/API
        // levels that don't support it, this is a no-op and the static
        // palette in themes.xml is used instead.
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Reflect the persisted "show logs" setting immediately on process start
        // (BLE reconnects, notification listener, etc. can fire before any
        // Activity/Fragment reads Prefs directly).
        NavLog.enabled = Prefs.showLogs(this)
    }
}
