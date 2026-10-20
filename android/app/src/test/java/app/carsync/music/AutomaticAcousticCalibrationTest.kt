package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class AutomaticAcousticCalibrationTest {
    @Test fun rejectsInsufficientAndImpossibleCaptures() {
        assertNull(AutomaticAcousticCalibration.summarize(listOf(null, 150L, null)))
        assertNull(AutomaticAcousticCalibration.summarize(listOf(-20L, 2500L, null)))
    }

    @Test fun robustMedianIsNotSingleGlitch() {
        val profile = AutomaticAcousticCalibration.summarize(listOf(124L, 122L, 128L))
        assertNotNull(profile)
        assertEquals(124L, profile!!.estimatedRoundTripMs)
        assertEquals(6L, profile.variationMs)
        assertTrue(profile.reliable)
    }

    @Test fun highVarianceCannotBeUsedAsCalibratedLatency() {
        val profile = AutomaticAcousticCalibration.summarize(listOf(40L, 250L, 480L))!!
        assertFalse(profile.reliable)
        assertFalse(AutomaticAcousticCalibration.routeChanged(40L, profile))
    }

    @Test fun detectsSpeakerRouteChangesOnlyAfterReliableProfile() {
        val profile = AutomaticAcousticCalibration.summarize(listOf(300L, 310L, 305L))!!
        assertTrue(AutomaticAcousticCalibration.routeChanged(150L, profile))
        assertFalse(AutomaticAcousticCalibration.routeChanged(250L, profile))
        assertFalse(AutomaticAcousticCalibration.routeChanged(null, profile))
    }
}
