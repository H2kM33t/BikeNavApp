package com.bikenav.navlistenertest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Speed straight from the phone's GPS, instead of scanning Maps' on-screen
 * text for a "XX km/h" string. That text only appears sometimes (varies by
 * Maps version/state) and is one more thing that can be mis-parsed; GPS
 * speed is always available as soon as location permission is granted and
 * doesn't depend on Maps displaying anything at all.
 *
 * Falls back gracefully: if permission isn't granted, or no GPS fix has
 * arrived yet, callers should keep using the accessibility-text speed as
 * before — see NavDataState's speed resolution.
 */
object GpsSpeedProvider : LocationListener {
    private const val MIN_UPDATE_INTERVAL_MS = 1000L
    private const val MIN_UPDATE_DISTANCE_M = 0f

    // A GPS fix older than this is considered stale (e.g. phone lost signal
    // in a tunnel/underpass) and NavDataState will fall back to text-based
    // speed rather than keep showing a frozen number.
    const val STALE_AFTER_MS = 10_000L

    @Volatile private var lastSpeedKmh: Int = 0
    @Volatile private var lastFixAt: Long = 0L
    private var locationManager: LocationManager? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            NavLog.post("GpsSpeedProvider: location permission not granted, GPS speed unavailable")
            return
        }

        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            NavLog.post("GpsSpeedProvider: LocationManager unavailable")
            return
        }
        locationManager = lm

        try {
            // GPS provider specifically (not NETWORK/FUSED) — most accurate
            // speed reading for a moving vehicle, which is all this needs.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_INTERVAL_MS,
                MIN_UPDATE_DISTANCE_M,
                this
            )
            started = true
            NavLog.post("GpsSpeedProvider: started")
        } catch (e: SecurityException) {
            NavLog.post("GpsSpeedProvider: start failed (${e.message})")
        }
    }

    fun stop(context: Context) {
        if (!started) return
        locationManager?.removeUpdates(this)
        started = false
        NavLog.post("GpsSpeedProvider: stopped")
    }

    /** Returns the last known speed in km/h, or null if no fresh fix is available. */
    fun currentSpeedKmhOrNull(): Int? {
        if (lastFixAt == 0L) return null
        if (System.currentTimeMillis() - lastFixAt > STALE_AFTER_MS) return null
        return lastSpeedKmh
    }

    override fun onLocationChanged(location: Location) {
        // Location.getSpeed() is metres/second; hasSpeed() is false on some
        // devices/fixes (e.g. first fix right after acquiring signal).
        if (location.hasSpeed()) {
            lastSpeedKmh = (location.speed * 3.6f).toInt().coerceIn(0, 255)
            lastFixAt = System.currentTimeMillis()
            NavDataState.updateGpsSpeed(lastSpeedKmh)
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith(""))
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        NavLog.post("GpsSpeedProvider: GPS provider disabled")
    }
}
