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
    /** Returns sample index of best match if normalized correlation is strong. */
    fun findStart(recording: ShortArray, signal: ShortArray = chirp()): Int? {
        if (signal.size > recording.size || signal.size<50) return null
        var signalEnergy=0.0
        for (x in signal) signalEnergy+=x.toDouble()*x
        if(signalEnergy==0.0)return null
        var bestIndex=-1
        var bestScore=0.0
        // 80-sample stride; refine around the strongest coarse candidate.
        for (start in 0..recording.size-signal.size step 80) {
            val score=score(recording,signal,start,signalEnergy)
            if(score>bestScore){bestScore=score;bestIndex=start}
        }
        if(bestIndex<0 || bestScore<.25)return null
        var fineIndex=bestIndex
        for(start in (bestIndex-80).coerceAtLeast(0)..(bestIndex+80).coerceAtMost(recording.size-signal.size)) {
            val score=score(recording,signal,start,signalEnergy)
            if(score>bestScore){bestScore=score;fineIndex=start}
        }
        return if(bestScore>=.32)fineIndex else null
    }
    private fun score(recording:ShortArray,signal:ShortArray,start:Int,energy:Double):Double {
        var dot=0.0
        var observed=0.0
        for(i in signal.indices){val x=recording[start+i].toDouble();dot+=x*signal[i];observed+=x*x}
        return if(observed==0.0)0.0 else dot/sqrt(energy*observed)
    }
}
