package com.bikenav.navlistenertest

import android.content.Context

/** Simple SharedPreferences wrapper for app settings. */
object Prefs {
    private const val PREFS_NAME = "bike_nav_prefs"
    private const val KEY_SHOW_LOGS = "show_logs"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_PAIRED_ADDRESS = "paired_device_address"
    private const val KEY_PAIRED_NAME = "paired_device_name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun showLogs(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LOGS, false)

    fun setShowLogs(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LOGS, value).apply()
        NavLog.enabled = value
    }

    fun autoReconnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECONNECT, true)

    fun setAutoReconnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
    }

    fun pairedDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_PAIRED_ADDRESS, null)

    fun pairedDeviceName(context: Context): String? =
        prefs(context).getString(KEY_PAIRED_NAME, null)

    fun setPairedDevice(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_PAIRED_ADDRESS, address)
            .putString(KEY_PAIRED_NAME, name)
            .apply()
    }

    fun clearPairedDevice(context: Context) {
        prefs(context).edit()
            .remove(KEY_PAIRED_ADDRESS)
            .remove(KEY_PAIRED_NAME)
            .apply()
    }
}
