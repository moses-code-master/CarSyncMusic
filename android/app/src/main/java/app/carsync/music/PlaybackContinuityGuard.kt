package app.carsync.music

import kotlin.math.abs

data class ContinuityDecision(
    val hardSeek: Boolean = false,
    val speed: Float? = null,
    val smoothedDriftMs: Long = 0L
)

/** Gradual synchronization that avoids rapid seek/speed jitter on Android head units. */
class PlaybackContinuityGuard {
    private var smoothed: Double? = null
    private var largeSinceMs: Long? = null
    private var lastSeekMs = -30_000L
    private var lastSpeedMs = -30_000L
    private var speed = 1f

    fun reset() {
        smoothed = null
        largeSinceMs = null
        lastSeekMs = -30_000L
        lastSpeedMs = -30_000L
        speed = 1f
    }

    fun next(driftMs: Long, timeMs: Long, radioActive: Boolean): ContinuityDecision {
        val estimate = (smoothed?.let { it * 0.82 + driftMs * 0.18 } ?: driftMs.toDouble())
        smoothed = estimate
        val severe = abs(driftMs) >= 1800 && abs(estimate) >= 1500 &&
            driftMs.toDouble() * estimate > 0
        if (severe) {
            if (largeSinceMs == null) largeSinceMs = timeMs
        } else largeSinceMs = null
        if (!radioActive && severe && timeMs - (largeSinceMs ?: timeMs) >= 1750 &&
            timeMs - lastSeekMs >= 15000) {
            lastSeekMs = timeMs
            largeSinceMs = null
            smoothed = null
            speed = 1f
            lastSpeedMs = timeMs
            return ContinuityDecision(true, 1f, estimate.toLong())
        }
        if (timeMs - lastSpeedMs < 1500) return ContinuityDecision(smoothedDriftMs = estimate.toLong())
        val target = if (radioActive || abs(estimate) < 120) 1f
            else (1.0 + estimate / 25000.0).coerceIn(0.992, 1.008).toFloat()
        if (abs(target - speed) < 0.001f)
            return ContinuityDecision(smoothedDriftMs = estimate.toLong())
        lastSpeedMs = timeMs
        speed = target
        return ContinuityDecision(speed = target, smoothedDriftMs = estimate.toLong())
    }
}
