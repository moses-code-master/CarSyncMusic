package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import kotlin.concurrent.thread

/** 16 kHz mono PCM is deliberately bounded to 20-ms WebSocket frames. Prototype transport. */
class ConvoyAudio(private val context: Context, private val player: () -> ExoPlayer?, private val send: (String) -> Unit, private val failed: (String) -> Unit = {}) {
    @Volatile private var recording = false
    private var capture: AudioRecord? = null
    private var playback: AudioTrack? = null
    private var originalVolume: Float? = null
    private val rate = 16000
    private val size = 640
    private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(20)
    private var playbackRunning = false
    fun duck(enabled: Boolean) {
        val p = player() ?: return
        if (enabled && originalVolume == null) {
            originalVolume = p.volume
            p.volume = p.volume * .2f
        } else if (!enabled) {
            originalVolume?.let { p.volume = it }
            originalVolume = null
        }
    }
    fun beginCapture(): Boolean {
        if (recording || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        var mic: AudioRecord? = null
        for (source in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            mic = try { AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, size * 4)) }
                catch (_: Exception) { null }
            if (mic?.state == AudioRecord.STATE_INITIALIZED) break
            mic?.release()
            mic = null
        }
        val microphone = mic ?: return false
        capture = microphone
        recording = true
        thread(name = "CarSync-PTT-capture", isDaemon = true) {
            try {
                microphone.startRecording()
                if (microphone.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Audio input failed to start")
                val buffer = ByteArray(size)
                while (recording) {
                    var done = 0
                    while (recording && done < size) {
                        val n = microphone.read(buffer, done, size - done)
                        if (n <= 0) { recording = false; break }
                        done += n
                    }
                    if (done == size) send(Base64.encodeToString(buffer, Base64.NO_WRAP))
                }
            } catch (e: Exception) {
                if (recording) failed("Microphone capture failed: ${e.message.orEmpty()}")
                recording = false
            } finally {
                runCatching { microphone.stop() }; microphone.release()
                if (capture === microphone) capture = null
            }
        }
        return true
    }
    fun stopCapture() { recording = false; runCatching { capture?.stop() } }
    fun receive(encoded: String) {
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { return }
        if (bytes.size != size) return
        if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
        if (playbackRunning) return
        playbackRunning = true
        thread(name = "CarSync-PTT-playback", isDaemon = true) {
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) { playbackRunning = false; return@thread }
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(maxOf(minimum, size * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
            playback = track
            try {
                track.play()
                while (playbackRunning) {
                    val frame = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    track.write(frame, 0, frame.size)
                }
            } catch (_: Exception) { } finally {
                runCatching { track.stop() }; track.release(); playback = null; playbackRunning = false
            }
        }
    }
    fun stopPlayback() { playbackRunning = false; queue.clear(); duck(false) }
    fun close() { stopCapture(); stopPlayback() }
}
