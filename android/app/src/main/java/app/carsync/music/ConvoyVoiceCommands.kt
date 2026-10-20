package app.carsync.music

import java.util.Locale

/** Deliberately small, non-generative command grammar. No remote commands are executed. */
enum class VoiceAction {
    PLAY, PAUSE, NEXT, PREVIOUS, START_TALK, STOP_TALK,
    SHOW_CARS, SHOW_MAP, SHOW_PLAYER, CALIBRATE, UNKNOWN
}

object ConvoyVoiceCommands {
    fun parse(utterance: String): VoiceAction {
        val clean = utterance.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim().replace(Regex("\\s+"), " ")
        val command = clean.removePrefix("hey carsync ").removePrefix("carsync ").trim()
        return when (command) {
            "pause music", "pause playback", "stop music" -> VoiceAction.PAUSE
            "play music", "resume music", "resume playback" -> VoiceAction.PLAY
            "next song", "next track", "skip song", "skip track" -> VoiceAction.NEXT
            "previous song", "previous track", "last song" -> VoiceAction.PREVIOUS
            "start talking", "talk to convoy", "start intercom" -> VoiceAction.START_TALK
            "stop talking", "end intercom", "release intercom" -> VoiceAction.STOP_TALK
            "show cars", "show convoy cars", "who is connected" -> VoiceAction.SHOW_CARS
            "show map", "show convoy map", "open live map" -> VoiceAction.SHOW_MAP
            "show player", "now playing" -> VoiceAction.SHOW_PLAYER
            "calibrate audio", "measure audio delay" -> VoiceAction.CALIBRATE
            else -> VoiceAction.UNKNOWN
        }
    }

    fun permitted(action: VoiceAction, connected: Boolean, canControl: Boolean,
                  speaking: Boolean, channelAvailable: Boolean, parked: Boolean): Boolean {
        if (!parked) return false
        return when (action) {
            VoiceAction.PLAY, VoiceAction.PAUSE, VoiceAction.NEXT, VoiceAction.PREVIOUS -> canControl
            VoiceAction.START_TALK -> connected && channelAvailable && !speaking
            VoiceAction.STOP_TALK -> connected && speaking
            VoiceAction.CALIBRATE -> !speaking
            VoiceAction.SHOW_CARS, VoiceAction.SHOW_MAP, VoiceAction.SHOW_PLAYER -> true
            VoiceAction.UNKNOWN -> false
        }
    }
}
