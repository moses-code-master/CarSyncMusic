package app.carsync.music

import kotlin.math.*

/** Chirp correlation detects a played probe within an audio recording; not a calibrated acoustic clock. */
object ChirpMath {
    const val SAMPLE_RATE = 16000
    const val SILENCE_SAMPLES = 4000 // 250 ms
    const val CHIRP_SAMPLES = 3200   // 200 ms
    fun chirp(): ShortArray = ShortArray(CHIRP_SAMPLES) { i ->
        val t = i.toDouble() / SAMPLE_RATE
        val duration = CHIRP_SAMPLES.toDouble() / SAMPLE_RATE
        val startHz=1000.0
        val slope=(6000.0-startHz)/duration
        val window = .5 - .5*cos(2*PI*i/(CHIRP_SAMPLES-1))
        (sin(2*PI*(startHz*t + slope*t*t/2))*window*11000).roundToInt().toShort()
    }
    /** FFT cross-correlation locates a chirp without losing high-frequency matches. */
    fun findStart(recording: ShortArray, signal: ShortArray = chirp()): Int? {
        if (signal.size > recording.size || signal.size < 50) return null
        var n = 1
        while (n < recording.size + signal.size - 1) n = n shl 1
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        val signalReal = DoubleArray(n)
        val signalImag = DoubleArray(n)
        var signalEnergy = 0.0
        for (i in signal.indices) {
            val value = signal[i].toDouble()
            signalReal[i] = value
            signalEnergy += value * value
        }
        if (signalEnergy == 0.0) return null
        for (i in recording.indices) real[i] = recording[i].toDouble()
        fft(real, imag, false)
        fft(signalReal, signalImag, false)
        for (i in 0 until n) {
            val ar = real[i]
            val ai = imag[i]
            val br = signalReal[i]
            val bi = signalImag[i]
            real[i] = ar * br + ai * bi
            imag[i] = ai * br - ar * bi
        }
        fft(real, imag, true)
        var observed = 0.0
        for (i in signal.indices) observed += recording[i].toDouble() * recording[i]
        var best = -1
        var confidence = 0.0
        for (start in 0..recording.size - signal.size) {
            if (observed > 0.0) {
                val score = real[start] / sqrt(signalEnergy * observed)
                if (score > confidence) { confidence = score; best = start }
            }
            if (start < recording.size - signal.size) {
                val exiting = recording[start].toDouble()
                val entering = recording[start + signal.size].toDouble()
                observed += entering * entering - exiting * exiting
            }
        }
        return if (confidence >= 0.32) best else null
    }
    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val theta = (if (inverse) 2.0 else -2.0) * PI / len
            val wr = cos(theta)
            val wi = sin(theta)
            var k = 0
            while (k < n) {
                var cr = 1.0
                var ci = 0.0
                for (i in 0 until len / 2) {
                    val u = k + i
                    val v = u + len / 2
                    val vr = real[v] * cr - imag[v] * ci
                    val vi = real[v] * ci + imag[v] * cr
                    real[v] = real[u] - vr
                    imag[v] = imag[u] - vi
                    real[u] += vr
                    imag[u] += vi
                    val nextCr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nextCr
                }
                k += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { real[i] /= n; imag[i] /= n }
    }
}
