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
import java.util.Locale

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

    fun start(allowProviderNetwork: Boolean): Boolean {
        if (listening) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status("Allow microphone permission to use voice commands.")
            return false
        }
        val useOffline = onDeviceAvailable()
        if (!useOffline && !allowProviderNetwork) {
            status("On-device voice recognition is unavailable. Turn on the optional system speech provider in Settings if you agree to its privacy policy.")
            return false
        }
        if (!useOffline && !SpeechRecognizer.isRecognitionAvailable(context)) {
            status("This Android head unit has no installed speech-recognition service.")
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
                    status(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No clear command recognized. Try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "Microphone is busy. Stop the intercom and retry."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is required."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition network unavailable."
                        else -> "Speech recognition failed (code $error)."
                    })
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline)
            }
            listening = true
            recognizer?.startListening(intent)
            status(if (useOffline) "Offline speech recognition active…" else
                "System speech provider active. It may process audio over the internet.")
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
