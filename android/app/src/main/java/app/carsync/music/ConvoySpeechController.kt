package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * User-activated, single-shot speech recognition. No background wake word.
 * Offline speech is preferred; cloud-based recognition must be explicitly opted into.
 */
class ConvoySpeechController(
    private val context: Context,
    private val status: (String) -> Unit,
    private val result: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(useSystemProvider: Boolean, language: VoiceLanguage = VoiceLanguage.ENGLISH_US): Boolean {
        if (listening) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status("Allow microphone permission to use voice commands.")
            return false
        }
        // The presence of an on-device recognizer does NOT guarantee that
        // the English language pack is installed; Android error 12 proves this.
        val useOffline = !useSystemProvider
        if (useOffline && !onDeviceAvailable()) {
            status("On-device recognition unavailable. You may opt into Android's system speech provider (which may send audio online).")
            return false
        }
        if (useSystemProvider && !SpeechRecognizer.isRecognitionAvailable(context)) {
            status("No Android system speech recognizer is installed. Install or enable one in Android Settings.")
            return false
        }
        return try {
            close()
            recognizer = if (useOffline && Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status("Listening for one command…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status("Recognizing command…") }
                override fun onError(error: Int) {
                    close()
                    status(VoiceRecognitionHelp.describe(error, useOffline, language))
                }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    close()
                    if (words.isBlank()) status("No command recognized.")
                    else result(words)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                language.tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline)
            }
            listening = true
            recognizer?.startListening(intent)
            status(if (useOffline) "Trying on-device ${language.label} speech recognition…" else
                "Android system speech provider: ${language.label}. Audio may be processed online.")
            true
        } catch (e: Exception) {
            close()
            status("Cannot start speech recognition: ${e.message.orEmpty()}")
            false
        }
    }

    fun close() {
        val old = recognizer
        recognizer = null
        listening = false
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }
}
