package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class CarAudioCatalogTest {
    @Test fun acceptsOnlyCanonicalAudioIdentifiers() {
        val hash = "a".repeat(64)
        assertEquals(hash, CarAudioCatalog.hash(CarAudioCatalog.mediaId(hash)))
        listOf(hash, "file:///private.mp3", "https://untrusted.test/audio", "audio:../secret", "audio:" + "z".repeat(64), "video:$hash").forEach { assertNull(CarAudioCatalog.hash(it)) }
    }
    @Test fun paginationIsBoundedAndOverflowSafe() {
        assertEquals(listOf(3,4), CarAudioCatalog.page((1..5).toList(), 1, 2))
        assertEquals(listOf(5), CarAudioCatalog.page((1..5).toList(), 2, 2))
        assertTrue(CarAudioCatalog.page(listOf(1), Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(CarAudioCatalog.page(listOf(1), -1, 2).isEmpty())
        assertTrue(CarAudioCatalog.page(listOf(1), 0, 0).isEmpty())
    }
    @Test fun connectedCarDisplayBlocksPicturesEvenWithParkedConfirmation() {
        val state = AppState(parkedConfirmed = true, driving = false, carHostConnected = true)
        assertFalse(state.showVideo)
        assertTrue(state.copy(carHostConnected = false).showVideo)
    }
}
