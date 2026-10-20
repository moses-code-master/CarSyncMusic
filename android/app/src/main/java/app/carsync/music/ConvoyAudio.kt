package app.carsync.music

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Foreground, server-floor-controlled voice radio.
 * PCM16 works with old servers; G.711 μ-law halves frame payload on new servers.
 * Audio effects use Android DSP when available and gracefully fall back if absent.
 */
class ConvoyAudio(
    private val context: Context,
    private val player: () -> ExoPlayer?,
    private val send: (codec: String, data: String) -> Unit,
    private val failed: (String) -> Unit = {},
    private val status: (String) -> Unit = {}
) {
    @Volatile private var recording = false
    @Volatile private var playbackRunning = false
    @Volatile private var compactVoice = false
    @Volatile private var microphone: AudioRecord? = null
    @Volatile private var speaker: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var suppression: NoiseSuppressor? = null
    private var gain: AutomaticGainControl? = null
    private var originalVolume: Float? = null
    private var duckAnimator: ValueAnimator? = null
    private val bluetoothRoute = BluetoothRadioRoute(context, status)
    private var preferBluetoothRadio = false

    fun configureBluetoothRadio(enabled: Boolean) {
        preferBluetoothRadio = enabled
        if (!enabled) bluetoothRoute.release()
    }

    fun beginVoiceSession() {
        bluetoothRoute.begin(preferBluetoothRadio)
    }

    fun endCallRoute() { bluetoothRoute.release() }
    private val rate = 16000
    private val pcmBytes = ConvoyVoiceCodec.PCM_BYTES
    private val queue = ArrayBlockingQueue<ByteArray>(18)
    @Volatile private var playbackGeneration = 0

    fun setCompactVoice(enabled: Boolean) {
        compactVoice = enabled
    }

    /** Smoothly reduce/recover shared music without pausing the player or seeking. */
    fun duck(enabled: Boolean) {
        val p = player() ?: return
        if (enabled && originalVolume == null) originalVolume = p.volume
        val target = if (enabled) (originalVolume ?: p.volume) * .22f
            else originalVolume ?: p.volume
        duckAnimator?.cancel()
        if (kotlin.math.abs(p.volume - target) > 0.001f) {
            duckAnimator = ValueAnimator.ofFloat(p.volume, target).apply {
                duration = 220L
                addUpdateListener { animation ->
                    if (player() === p) p.volume = animation.animatedValue as Float
                }
                start()
            }
        }
        if (!enabled) originalVolume = null
    }

    private fun applyVoiceProcessing(record: AudioRecord) {
        val session = record.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(session)
                echo?.enabled = true
            }
        } catch (_: Exception) { echo = null }
        try {
            if (NoiseSuppressor.isAvailable()) {
                suppression = NoiseSuppressor.create(session)
                suppression?.enabled = true
            }
        } catch (_: Exception) { suppression = null }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gain = AutomaticGainControl.create(session)
                gain?.enabled = true
            }
        } catch (_: Exception) { gain = null }
        status("Radio mic ready · noise suppression ${if (suppression?.enabled == true) "on" else "unavailable"} · echo reduction ${if (echo?.enabled == true) "on" else "unavailable"}")
    }

    private fun releaseEffects() {
        runCatching { echo?.release() }
        runCatching { suppression?.release() }
        runCatching { gain?.release() }
        echo = null; suppression = null; gain = null
    }

    fun beginCapture(): Boolean {
        if (recording || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        var record: AudioRecord? = null
        for (source in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            record = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, pcmBytes * 4))
            } catch (_: Exception) { null }
            if (record?.state == AudioRecord.STATE_INITIALIZED) break
            runCatching { record?.release() }
            record = null
        }
        val capture = record ?: return false
        applyVoiceProcessing(capture)
        microphone = capture
        recording = true
        thread(name = "CarSync-radio-capture", isDaemon = true) {
            try {
                capture.startRecording()
                if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                    error("Audio input did not start")
                val buffer = ByteArray(pcmBytes)
                var failures = 0
                while (recording) {
                    var count = 0
                    while (recording && count < pcmBytes) {
                        val n = capture.read(buffer, count, pcmBytes - count)
                        if (n <= 0) {
                            if (!recording) break
                            failures++
                            if (failures >= 3) error("Microphone stopped returning samples ($n)")
                            continue
                        }
                        failures = 0
                        count += n
                    }
                    if (count == pcmBytes && recording) {
                        val compact = compactVoice
                        val body = if (compact) ConvoyVoiceCodec.encode(buffer) else buffer
                        send(if (compact) "mulaw16" else "pcm16",
                            Base64.encodeToString(body, Base64.NO_WRAP))
                    }
                }
            } catch (e: Exception) {
                if (recording) failed("Radio microphone failed: ${e.message.orEmpty()}")
                recording = false
            } finally {
                runCatching { capture.stop() }
                releaseEffects()
                runCatching { capture.release() }
                if (microphone === capture) microphone = null
            }
        }
        return true
    }

    fun stopCapture() {
        recording = false
        runCatching { microphone?.stop() }
    }

    fun receive(data: String, codec: String) {
        val encoded = try { Base64.decode(data, Base64.DEFAULT) } catch (_: Exception) { return }
        val frame = when (codec) {
            "mulaw16" -> if (encoded.size == ConvoyVoiceCodec.COMPACT_BYTES)
                ConvoyVoiceCodec.decode(encoded) else return
            "pcm16" -> if (encoded.size == pcmBytes) encoded else return
            else -> return
        }
        if (!queue.offer(frame)) { queue.poll(); queue.offer(frame) }
        if (playbackRunning) return
        playbackRunning = true
        val generation = ++playbackGeneration
        thread(name = "CarSync-radio-playback", isDaemon = true) {
            val minimum = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) {
                playbackRunning = false
                failed("Radio speaker cannot initialize for 16 kHz PCM.")
                return@thread
            }
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(if (bluetoothRoute.isCallAudioActive())
                            AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, pcmBytes * 6))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                playbackRunning = false
                failed("Radio speaker unavailable: ${e.message.orEmpty()}")
                return@thread
            }
            speaker = track
            try {
                track.play()
                // Discard old voice rather than playing seconds behind live speech.
                while (playbackRunning && playbackGeneration == generation) {
                    val packet = queue.poll(500, TimeUnit.MILLISECONDS) ?: break
                    if (track.write(packet, 0, packet.size) < 0)
                        error("Audio speaker write failed")
                }
            } catch (e: Exception) {
                if (playbackRunning && playbackGeneration == generation)
                    failed("Radio output interrupted: ${e.message.orEmpty()}")
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                if (speaker === track) speaker = null
                if (playbackGeneration == generation) playbackRunning = false
            }
        }
    }

    fun stopPlayback() {
        playbackGeneration++
        playbackRunning = false
        queue.clear()
        runCatching { speaker?.pause() }
        runCatching { speaker?.flush() }
        duck(false)
    }

    fun close() {
        stopCapture()
        stopPlayback()
        bluetoothRoute.release()
    }
}
