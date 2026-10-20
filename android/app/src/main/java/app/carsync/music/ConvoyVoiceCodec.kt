package app.carsync.music

/**
 * G.711 μ-law companding of 16 kHz mono signed PCM16 frames.
 * This halves voice frame size (640 -> 320 bytes), but is NOT Opus.
 * The existing PCM16 transport remains the compatibility fallback.
 */
object ConvoyVoiceCodec {
    const val PCM_BYTES = 640
    const val COMPACT_BYTES = 320
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size == PCM_BYTES)
        return ByteArray(COMPACT_BYTES) { i ->
            val lo = pcm[i * 2].toInt() and 255
            val hi = pcm[i * 2 + 1].toInt()
            encodeSample(((hi shl 8) or lo).toShort().toInt())
        }
    }

    fun decode(compact: ByteArray): ByteArray {
        require(compact.size == COMPACT_BYTES)
        val pcm = ByteArray(PCM_BYTES)
        compact.forEachIndexed { i, byte ->
            val value = decodeSample(byte)
            pcm[i * 2] = value.toByte()
            pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        return pcm
    }

    fun encodeSample(value: Int): Byte {
        val sign = if (value < 0) 0x80 else 0
        var magnitude = if (value < 0) -value else value
        magnitude = magnitude.coerceAtMost(CLIP) + BIAS
        var exponent = 0
        var ceiling = 0xFF
        while (magnitude > ceiling && exponent < 7) {
            ceiling = (ceiling shl 1) + 1
            exponent++
        }
        val mantissa = (magnitude shr (exponent + 3)) and 15
        return ((sign or (exponent shl 4) or mantissa) xor 0xFF).toByte()
    }

    fun decodeSample(input: Byte): Int {
        val u = (input.toInt() xor 0xFF) and 0xFF
        val exponent = (u shr 4) and 7
        val mantissa = u and 15
        val expanded = ((mantissa shl 3) + BIAS) shl exponent
        return if ((u and 0x80) != 0) BIAS - expanded else expanded - BIAS
    }
}
