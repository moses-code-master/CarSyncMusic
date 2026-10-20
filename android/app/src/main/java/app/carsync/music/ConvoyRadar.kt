package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Location remains opt-in. The server keeps only the last live observation in memory. */
class ConvoyRadar(private val context: Context, private val publish: (Location?) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { if (active) publish(location) }
        @Deprecated("Older Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    fun start(): Boolean {
        if (active) return true
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false
        val provider = if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        return try { manager.requestLocationUpdates(provider, 2000L, 5f, listener); active = true; true } catch (_: Exception) { false }
    }
    fun stop() {
        active = false
        manager.removeUpdates(listener)
        publish(null)
    }
}
