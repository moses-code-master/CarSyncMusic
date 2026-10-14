package app.carsync.music
import org.junit.Assert.*
import org.junit.Test
class SyncMathTest {
    @Test fun asymmetricClockAndOutlier() {
        val c = ClockEstimator()
        repeat(5) { c.add(1000.0, 1510.0, 1511.0, 1021.0) }
        c.add(2000.0, 2990.0, 2991.0, 3001.0)
        assertTrue(c.ready); assertEquals(500.0, c.offsetMs, 0.01); assertEquals(20.0, c.rttMs, 0.01)
    }
    @Test fun positionsAndCorrection() {
        assertEquals(2500L, PlaybackFrame(0, 500, true, 1000.0, 1).target(3000.0))
        assertEquals(500L, PlaybackFrame(0, 500, false, 1000.0, 1).target(3000.0))
        assertEquals(1.02f, driftSpeed(1000), 0.001f); assertEquals(0.98f, driftSpeed(-1000), 0.001f)
    }
    @Test fun rejectsPublicHostsMasqueradingAsPrivatePrefixes() {
        assertTrue(isLanHost("192.168.1.3")); assertTrue(isLanHost("172.20.1.3")); assertTrue(isLanHost("10.0.2.2"))
        assertFalse(isLanHost("192.168.evil.example")); assertFalse(isLanHost("10.example.com")); assertFalse(isLanHost("172.32.1.1"))
    }
}
