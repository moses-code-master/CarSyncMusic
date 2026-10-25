package app.carsync.music

import kotlin.math.abs

/**
 * Median of repeated speaker→mic loopback timings.
 * This is device diagnostics, NOT a calibrated cross-device output-only latency.
 */
data class AcousticProfile(
    val estimatedRoundTripMs: Long,
    val variationMs: Long,
    val acceptedMeasurements: Int,
    val reliable: Boolean
)
object AutomaticAcousticCalibration {
    fun summarize(samples: List<Long?>): AcousticProfile? {
        val valid = samples.filterNotNull().filter { it in 0..1200L }.sorted()
        if (valid.size < 2) return null
        val estimate = valid[valid.size / 2]
        val deviation = (valid.last() - valid.first())
        return AcousticProfile(
            estimatedRoundTripMs = estimate,
            variationMs = deviation,
            acceptedMeasurements = valid.size,
            reliable = valid.size >= 3 && deviation <= 80L
        )
    }

    /** Flag a changed acoustic route after a new test; never change sync offset silently. */
    fun routeChanged(previousMs: Long?, current: AcousticProfile): Boolean =
        previousMs != null && current.reliable &&
            abs(previousMs - current.estimatedRoundTripMs) > 100L
}
