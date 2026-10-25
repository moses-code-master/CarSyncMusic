package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Explicitly opted-in GPS updates. Listen to both location providers when
 * permitted, since many Android head units have no usable satellite GPS.
 * Never publish an old cached location as if it were live.
 */
class ConvoyRadar(private val context: Context, private val publish: (Location?) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false
    private val providers = mutableSetOf<String>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (active && freshEnough(location)) publish(location)
        }
        @Deprecated("Older Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            // If another registered provider works, preserve the session;
            // otherwise revoke the last position on the server.
            if (active && providers.none { name ->
                runCatching { manager.isProviderEnabled(name) }.getOrDefault(false)
            }) publish(null)
        }
    }

    private fun freshEnough(location: Location): Boolean {
        if (!location.hasAccuracy() || location.accuracy > 250f || location.accuracy < 0f) return false
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
        val ageNs = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return ageNs >= 0 && ageNs < 30_000_000_000L
    }

    fun start(): Boolean {
        if (active) return true
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false
        active = true
        providers.clear()
        // Satellite GPS can take a long time to acquire a fix indoors.
        // A permitted network fix is also useful for convoy locations.
        for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (name == LocationManager.GPS_PROVIDER && !fine) continue
            val available = runCatching { manager.isProviderEnabled(name) }.getOrDefault(false)
            if (!available) continue
            try {
                manager.requestLocationUpdates(name, 4000L, 0f, listener)
                providers.add(name)
            } catch (_: SecurityException) {
                // Gracefully try remaining available providers.
            } catch (_: IllegalArgumentException) {}
        }
        if (providers.isEmpty()) {
            active = false
            return false
        }
        // Only seed an actually recent accurate fix; do not infer coordinates
        // or share cached positions from before this opt-in.
        val newest = providers.mapNotNull { name ->
            runCatching { manager.getLastKnownLocation(name) }.getOrNull()
        }.filter(::freshEnough).maxByOrNull { it.elapsedRealtimeNanos }
        if (newest != null) publish(newest)
        return true
    }

    fun stop() {
        active = false
        providers.clear()
        runCatching { manager.removeUpdates(listener) }
        publish(null)
    }
}
