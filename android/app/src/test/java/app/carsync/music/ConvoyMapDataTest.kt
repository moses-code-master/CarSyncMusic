package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class ConvoyMapDataTest {
    private fun point(lat: Double, lon: Double, at: Long = 200000L, accuracy: Double = 8.0) =
        ConvoyPosition(lat, lon, 0.0, 0.0, accuracy, at)
    private val cars = listOf(
        Car("Host", "host", true, null, null, true, false, "h", point(0.0,0.0)),
        Car("You", "guest", true, null, null, true, false, "you", point(0.01,0.0)),
        Car("Far car", "guest", true, null, null, true, false, "f", point(0.03,0.0)),
        Car("Private", "guest", true, null, null, true, false, "p", null),
        Car("Old fix", "guest", true, null, null, false, false, "old", point(0.9,0.0, 1000L)),
        Car("Offline", "guest", false, null, null, false, false, "gone", point(1.0,0.0)),
        Car("Inaccurate", "guest", true, null, null, false, false, "noise", point(0.8,0.0,200000L,600.0))
    )

    @Test fun noSessionShowsNoLocations() {
        val result = ConvoyMapData.build(AppState(cars=cars), null, 200200L)
        assertTrue(result.cars.isEmpty())
        assertNull(result.referenceId)
    }

    @Test fun rosterShowsEveryCarButOnlyApprovedFreshLocationsAreMapped() {
        val result = ConvoyMapData.build(AppState(connected=true, memberId="you",cars=cars),null,200200L)
        assertEquals(7,result.cars.size)
        assertEquals(6,result.onlineCount)
        assertEquals(3,result.locatedCount)
        assertEquals("you",result.referenceId)
        assertEquals(0.0,result.cars[1].distanceFromReferenceMeters!!,0.1)
        assertEquals("Location not shared or unavailable",result.cars[3].locationStatus)
        assertEquals("GPS stale or inaccurate",result.cars[4].locationStatus)
        assertEquals("Offline",result.cars[5].locationStatus)
        assertNull(result.cars[3].distanceFromReferenceMeters)
        assertNull(result.cars[4].usableLocation)
    }

    @Test fun choosingAnotherCarRecomputesDistancesWithoutInferringRoadRoutes() {
        val state=AppState(connected=true,memberId="you",cars=cars)
        val fromMe=ConvoyMapData.build(state,null,200200L)
        val fromHost=ConvoyMapData.build(state,"h",200200L)
        assertEquals("h",fromHost.referenceId)
        assertTrue(fromHost.cars[2].distanceFromReferenceMeters!! > fromMe.cars[2].distanceFromReferenceMeters!!)
        val between=fromHost.cars[1].distanceFromReferenceMeters!!
        assertTrue(between in 1100.0..1125.0)
    }

    @Test fun inaccessibleSelectedReferenceFallsBackToMyLocationOrHost() {
        val state=AppState(connected=true,memberId="you",cars=cars)
        assertEquals("you",ConvoyMapData.build(state,"p",200200L).referenceId)
        assertEquals("h",ConvoyMapData.build(state.copy(memberId="p"),"p",200200L).referenceId)
    }
}
