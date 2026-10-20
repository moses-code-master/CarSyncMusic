package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class PlaybackContinuityGuardTest {
    @Test fun timingNoiseDoesNotSeekOrChangePlaybackSpeed() {
        val guard = PlaybackContinuityGuard()
        for (i in 0..60) {
            val drift = if (i % 3 == 0) 270L else if (i % 3 == 1) -230L else 30L
            val result = guard.next(drift, i * 250L, false)
            assertFalse("Clock spikes should not seek", result.hardSeek)
            assertTrue("Soft speed must stay within a small band",
                result.speed == null || result.speed in 0.992f..1.008f)
        }
    }

    @Test fun largeTransientDoesNotCauseDisruptiveSeek() {
        val guard = PlaybackContinuityGuard()
        for (i in 0..10) {
            val result = guard.next(if (i == 4) 2500L else 40L, i * 250L, false)
            assertFalse(result.hardSeek)
        }
    }

    @Test fun genuinelyOutOfSyncPlaybackEventuallyRecoversButDoesNotLoopSeek() {
        val guard = PlaybackContinuityGuard()
        var seeks = 0
        for (i in 0..65) {
            val decision = guard.next(2500L, i * 250L, false)
            if (decision.hardSeek) seeks++
        }
        assertEquals(1, seeks)
    }

    @Test fun radioAnnouncementSuppressesSeekAndReturnsToNormalSpeed() {
        val guard = PlaybackContinuityGuard()
        for (i in 0..30) {
            val decision = guard.next(2600L, i * 250L, true)
            assertFalse(decision.hardSeek)
            if (decision.speed != null) assertEquals(1f, decision.speed!!, 0.0001f)
        }
    }

    @Test fun sustainedSmallDriftUsesMinimalSpeedCorrection() {
        val guard = PlaybackContinuityGuard()
        val changes = (0..35).map { guard.next(350L, it * 250L, false) }
            .mapNotNull { it.speed }
        assertTrue(changes.any { it > 1f })
        assertTrue(changes.size <= 3)
        assertTrue(changes.all { it <= 1.008f })
    }

    @Test fun guardResetsAcrossTrackTransitions() {
        val guard = PlaybackContinuityGuard()
        for (i in 0..25) guard.next(2800L, i * 250L, false)
        guard.reset()
        assertFalse(guard.next(40L, 7000L, false).hardSeek)
    }
}
