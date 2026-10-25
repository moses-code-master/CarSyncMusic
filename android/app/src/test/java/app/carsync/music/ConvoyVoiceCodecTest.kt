package app.carsync.music

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ConvoyVoiceCodecTest {
    @Test fun silenceAndSpeechRoundTripInHalfSizedFrames() {
        val silence = ByteArray(640)
        val compact = ConvoyVoiceCodec.encode(silence)
        assertEquals(320, compact.size)
        val back = ConvoyVoiceCodec.decode(compact)
        assertEquals(640, back.size)
        for (i in 0 until 320) {
            val sample = ((back[i * 2 + 1].toInt() shl 8) or (back[i * 2].toInt() and 255)).toShort().toInt()
            assertTrue(abs(sample) < 200)
        }
    }

    @Test fun typicalVoiceAmplitudesRemainRecognizable() {
        val pcm = ByteArray(640)
        for (i in 0 until 320) {
            val sample = (16000.0 * sin(i * 2.0 * Math.PI * 300.0 / 16000)).toInt().toShort()
            pcm[i * 2] = sample.toByte()
            pcm[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        val restored = ConvoyVoiceCodec.decode(ConvoyVoiceCodec.encode(pcm))
        var worst = 0
        for (i in 0 until 320) {
            val original = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 255)).toShort().toInt()
            val rebuilt = ((restored[i * 2 + 1].toInt() shl 8) or (restored[i * 2].toInt() and 255)).toShort().toInt()
            worst = maxOf(worst, abs(original - rebuilt))
        }
        assertTrue("Distortion above μ-law quantization expectations: $worst", worst < 1500)
    }

    @Test fun malformedFramesAreRejected() {
        try { ConvoyVoiceCodec.encode(ByteArray(10)); fail("Should reject invalid PCM") } catch (_: IllegalArgumentException) {}
        try { ConvoyVoiceCodec.decode(ByteArray(100)); fail("Should reject invalid μ-law") } catch (_: IllegalArgumentException) {}
    }
}
