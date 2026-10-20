package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class ConvoyDashboardDataTest {
    private fun position(lat: Double, lon: Double, timestamp: Long = 100000L, accuracy: Double = 10.0) =
        ConvoyPosition(lat, lon, 0.0, 0.0, accuracy, timestamp)

    @Test fun noSessionDoesNotDisplayInventedCars() {
        val state = AppState(cars = listOf(Car("Ghost", "guest", true, null, null, true, false, "g")))
        val result = ConvoyDashboardData.from(state, 100500L)
        assertEquals(0, result.onlineCars)
        assertNull(result.farthestMeters)
        assertEquals("—", result.farthestLabel)
    }

    @Test fun farthestIsMeasuredFromThisCarAndIgnoresOfflineAndInaccurateFixes() {
        val now = 100500L
        val state = AppState(connected = true, memberId = "self", cars = listOf(
            Car("You", "host", true, null, null, true, false, "self", position(0.0, 0.0)),
            Car("Nearby", "guest", true, null, null, true, false, "a", position(0.001, 0.0)),
            Car("Farthest", "guest", true, null, null, true, false, "b", position(0.019, 0.0)),
            Car("Offline", "guest", false, null, null, false, false, "c", position(0.9, 0.0)),
            Car("Stale", "guest", true, null, null, false, false, "d", position(0.8, 0.0, 1L)),
            Car("Inaccurate", "guest", true, null, null, false, false, "e", position(0.8, 0.0, now, 600.0))
        ))
        val result = ConvoyDashboardData.from(state, now)
        assertEquals(5, result.onlineCars)
        assertEquals(6, result.registeredCars)
        assertEquals("Farthest", result.farthestName)
        assertTrue(result.farthestMeters!! in 2100.0..2150.0)
        assertEquals(3, result.availableLocations)
    }

    @Test fun withheldOwnLocationShowsUnknownDistance() {
        val result = ConvoyDashboardData.from(AppState(
            connected = true, memberId = "self",
            cars = listOf(
                Car("You", "host", true, null, null, true, false, "self", null),
                Car("Guest", "guest", true, null, null, true, false, "guest", position(0.02,0.0))
            )
        ), 100500L)
        assertEquals("—", result.farthestLabel)
        assertNull(result.farthestName)
        assertEquals(1, result.availableLocations)
    }
}
