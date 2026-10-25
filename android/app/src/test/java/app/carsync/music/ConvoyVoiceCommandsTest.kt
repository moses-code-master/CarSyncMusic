package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class ConvoyVoiceCommandsTest {
    @Test fun supportedPhrasesMapToRestrictedCommands() {
        val cases = mapOf(
            "CarSync pause music" to VoiceAction.PAUSE,
            "Hey CarSync, resume music." to VoiceAction.PLAY,
            "Next track" to VoiceAction.NEXT,
            "CarSync previous song" to VoiceAction.PREVIOUS,
            "talk to convoy" to VoiceAction.START_TALK,
            "stop talking" to VoiceAction.STOP_TALK,
            "show convoy map" to VoiceAction.SHOW_MAP,
            "who is connected" to VoiceAction.SHOW_CARS,
            "measure audio delay" to VoiceAction.CALIBRATE
        )
        cases.forEach { (spoken, expected) -> assertEquals(spoken, expected, ConvoyVoiceCommands.parse(spoken)) }
    }

    @Test fun unrecognizedOrDangerousPhrasesCannotTriggerActions() {
        for (spoken in listOf("", "open the door", "talking to someone",
                "car sync pause the meeting", "upload all songs", "delete playlist")) {
            assertEquals(spoken, VoiceAction.UNKNOWN, ConvoyVoiceCommands.parse(spoken))
        }
    }

    @Test fun guestsCannotOverrideHostMusicPermissionsAndVoiceCannotOpenMovingMap() {
        assertFalse(ConvoyVoiceCommands.permitted(VoiceAction.PAUSE, true, false, false, true, true))
        assertTrue(ConvoyVoiceCommands.permitted(VoiceAction.START_TALK, true, false, false, true, true))
        assertFalse(ConvoyVoiceCommands.permitted(VoiceAction.START_TALK, true, true, false, false, true))
        assertFalse(ConvoyVoiceCommands.permitted(VoiceAction.START_TALK, true, true, false, true, false))
        assertFalse(ConvoyVoiceCommands.permitted(VoiceAction.SHOW_MAP, true, true, false, true, false))
        assertTrue(ConvoyVoiceCommands.permitted(VoiceAction.STOP_TALK, true, false, true, false, true))
    }
}
