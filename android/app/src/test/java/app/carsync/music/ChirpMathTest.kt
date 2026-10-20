package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class ChirpMathTest {
    @Test fun recoversProbePosition() {
        val tone=ChirpMath.chirp()
        val recording=ShortArray(18000)
        val offset=6103
        tone.forEachIndexed { i, value -> recording[offset+i]=value }
        val found=ChirpMath.findStart(recording)
        assertNotNull(found)
        assertTrue(kotlin.math.abs(found!!-offset)<4)
    }
    @Test fun rejectsSilentRecording() {
        assertNull(ChirpMath.findStart(ShortArray(18000)))
    }
}
