package app.carsync.music
import org.junit.Test
import org.junit.Assert.*
class VideoSafetyPolicyTest {
    @Test fun requiresParkedConfirmationAndFailsClosedForVehicleRestrictions() {
        assertFalse(VideoSafetyPolicy.canShowPicture(false,false,false,false,false,false))
        assertTrue(VideoSafetyPolicy.canShowPicture(true,false,false,false,false,false))
        assertFalse(VideoSafetyPolicy.canShowPicture(true,true,false,false,false,false))
        assertFalse(VideoSafetyPolicy.canShowPicture(true,false,true,false,false,false))
        assertFalse(VideoSafetyPolicy.canShowPicture(true,false,false,true,false,false))
        assertFalse(VideoSafetyPolicy.canShowPicture(true,false,false,true,true,true))
        assertTrue(VideoSafetyPolicy.canShowPicture(true,false,false,true,true,false))
    }
}
