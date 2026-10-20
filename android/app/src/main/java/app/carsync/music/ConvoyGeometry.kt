package app.carsync.music

import kotlin.math.*

/** Share only fresh, reasonably accurate positions. Distances are straight-line estimates. */
data class ConvoyPosition(
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Double,
    val bearing: Double,
    val accuracyMeters: Double,
    val receivedAtMs: Long
) {
    fun usable(nowMs: Long) = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters in 0.0..250.0 && nowMs >= receivedAtMs && nowMs - receivedAtMs < 15000L
}

object ConvoyGeometry {
    fun separationMeters(a: ConvoyPosition?, b: ConvoyPosition?, nowMs: Long): Double? {
        if (a?.usable(nowMs) != true || b?.usable(nowMs) != true) return null
        val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
        val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
        val t = sin(latitudeDelta / 2).pow(2.0) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(longitudeDelta / 2).pow(2.0)
        return 6371000.0 * 2.0 * atan2(sqrt(t.coerceIn(0.0,1.0)),sqrt((1.0-t).coerceIn(0.0,1.0)))
    }
    fun label(meters: Double): String = if(meters < 1000.0) "${meters.roundToInt()} m away"
      else String.format(java.util.Locale.US, "%.1f km away", meters/1000.0)
    fun alert(meters: Double): Boolean = meters >= 2500.0
}
