package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Parked-only speaker-to-microphone loopback estimate. Does not estimate inter-device audio clock. */
class AcousticProbe(private val context: Context) {
    suspend fun measure(): Long? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return@withContext null
        val sampleRate=ChirpMath.SAMPLE_RATE
        val min=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0)return@withContext null
        var recorder: AudioRecord? = null
        var output: AudioTrack? = null
        try {
            recorder=AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(min,32768)).build()
            if(recorder.state != AudioRecord.STATE_INITIALIZED)return@withContext null
            val samples=ShortArray(ChirpMath.SILENCE_SAMPLES+ChirpMath.CHIRP_SAMPLES)
            ChirpMath.chirp().copyInto(samples,ChirpMath.SILENCE_SAMPLES)
            output=AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size*2).build()
            if(output.state != AudioTrack.STATE_INITIALIZED)return@withContext null
            if(output.write(samples,0,samples.size) != samples.size)return@withContext null
            output.setVolume(.3f)
            val captured=ShortArray(sampleRate*2)
            recorder.startRecording()
            output.play()
            var count=0
            while(count<captured.size){
                val n=recorder.read(captured,count,captured.size-count)
                if(n<=0)break
                count+=n
            }
            val start=ChirpMath.findStart(captured.copyOf(count)) ?: return@withContext null
            ((start-ChirpMath.SILENCE_SAMPLES)*1000L/sampleRate).coerceIn(0L,1200L)
        } catch (_: Exception) { null }
        finally {
            runCatching { recorder?.stop() }; runCatching { output?.stop() }
            recorder?.release();output?.release()
        }
    }
}
