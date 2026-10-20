package app.carsync.music

/** Language selection for a user's one-shot recognition request. */
enum class VoiceLanguage(val label: String, val tag: String?) {
    ENGLISH_US("English (US)", "en-US"),
    ENGLISH_UK("English (UK)", "en-GB"),
    DEVICE_DEFAULT("Device default", null);

    fun next(): VoiceLanguage = when (this) {
        ENGLISH_US -> ENGLISH_UK
        ENGLISH_UK -> DEVICE_DEFAULT
        DEVICE_DEFAULT -> ENGLISH_US
    }
}

/** Translate speech-engine failures into actionable advice instead of opaque Android numbers. */
object VoiceRecognitionHelp {
    fun describe(error: Int, offline: Boolean, language: VoiceLanguage): String = when (error) {
        12 -> "Language not supported by the ${if (offline) "on-device" else "system"} speech recognizer (${language.label}). Tap Language to try English (US), English (UK), or Device default. If offline English isn't installed, download it in Android speech settings; alternatively opt into the system speech provider."
        13 -> "The speech language pack is not installed or unavailable. Download an English voice-recognition pack in Android settings, or choose a different language/consented system provider."
        1, 2 -> "Network speech recognition is unavailable. Check internet access or use a downloaded on-device language."
        3 -> "The recognizer cannot capture audio. Check microphone access and stop the intercom."
        5 -> "The speech engine failed to initialize. Close this dialog and retry."
        6, 7 -> "No clear command recognized. Try again in a quieter car."
        8 -> "The microphone is busy. Stop the intercom and retry."
        9 -> "Microphone permission is required to use voice commands."
        10 -> "Too many requests to the speech service. Wait and try again."
        11 -> "The speech-recognition service disconnected. Retry or switch to another engine."
        else -> "Speech recognition failed (Android code $error). Try a different language or speech engine."
    }
}
