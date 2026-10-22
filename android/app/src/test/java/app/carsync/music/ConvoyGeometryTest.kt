package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class ConvoyGeometryTest {
    private fun at(lat:Double,lon:Double,now:Long=20000,accuracy:Double=10.0) =
        ConvoyPosition(lat,lon,0.0,0.0,accuracy,now)
    @Test fun measuresShortDistance() {
        val d = ConvoyGeometry.separationMeters(at(-1.2864,36.8172),at(-1.2864,36.819),20000)!!
        assertTrue(d > 150 && d < 250)
        assertFalse(ConvoyGeometry.alert(d))
    }
    @Test fun detectsLargeSeparation() {
        val d = ConvoyGeometry.separationMeters(at(-1.28,36.8),at(-1.28,36.83),20000)!!
        assertTrue(d > 2500)
        assertTrue(ConvoyGeometry.alert(d))
    }
    @Test fun retainsRecentlySharedGpsButExpiresLostCar() {
        val point = at(-1.28, 36.8, 1000)
        assertTrue(point.usable(30999))
        assertTrue(point.usable(35999))
        assertFalse(point.usable(36000))
        assertFalse(point.usable(900))
    }

    @Test fun ignoresStaleOrUnreliableCoordinates() {
        assertNull(ConvoyGeometry.separationMeters(at(-1.28,36.8,1),at(-1.28,36.83),50000))
        assertNull(ConvoyGeometry.separationMeters(at(-1.28,36.8,20000,500.0),at(-1.28,36.83),20000))
    }
}
