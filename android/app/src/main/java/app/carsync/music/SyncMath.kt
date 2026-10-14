package app.carsync.music

import kotlin.math.*
data class ClockSample(val offset: Double, val rtt: Double)
class ClockEstimator {
    private val samples = ArrayDeque<ClockSample>()
    var offsetMs = 0.0; private set
    var rttMs = 0.0; private set
    val ready get() = samples.size >= 3
    fun reset() { samples.clear(); offsetMs = 0.0; rttMs = 0.0 }
    fun add(t0: Double, t1: Double, t2: Double, t3: Double) {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt < 0 || rtt > 3000) return
        samples.addLast(ClockSample(((t1 - t0) + (t2 - t3)) / 2, rtt))
        if (samples.size > 20) samples.removeFirst()
        val best = samples.sortedBy { it.rtt }.take(5)
        offsetMs = best.map { it.offset }.sorted()[best.size / 2]
        rttMs = best.map { it.rtt }.average()
    }
}
data class PlaybackFrame(val index: Int, val positionMs: Long, val playing: Boolean, val executeAt: Double, val revision: Long) {
    fun target(serverNow: Double, latencyMs: Long = 0): Long = (positionMs + if (playing) max(0.0, serverNow + latencyMs - executeAt) else 0.0).toLong()
}
fun driftSpeed(errorMs: Long): Float = (1.0 + errorMs / 10000.0).coerceIn(0.98, 1.02).toFloat()
fun isLanHost(host: String): Boolean {
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
}
