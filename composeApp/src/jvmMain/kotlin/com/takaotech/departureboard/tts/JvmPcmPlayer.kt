package com.takaotech.departureboard.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.min
import kotlin.math.roundToInt

/** PCM su desktop con `javax.sound`: una linea per riproduzione, così i suoni si sovrappongono. */
class JvmPcmPlayer : PcmPlayer {

    override suspend fun play(samples: ShortArray, sampleRate: Int, gain: StateFlow<Float>) {
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            // Buffer corto: il guadagno si applica pezzo per pezzo mentre si scrive, e un cambio
            // si sente dopo al più il buffer (~190 ms a 44,1 kHz).
            line.open(format, CHUNK_SAMPLES * 2 * BUFFER_CHUNKS)
            line.start()
            try {
                // A pezzi: write() blocca finché il buffer della linea non si libera, e tra un
                // pezzo e l'altro una cancellazione interrompe il suono.
                var from = 0
                var lastGain = gain.value
                while (from < samples.size) {
                    ensureActive()
                    val to = min(from + CHUNK_SAMPLES, samples.size)
                    val nextGain = gain.value
                    val bytes = pcmBytes(samples, from, to, lastGain, nextGain)
                    var offset = 0
                    while (offset < bytes.size) offset += line.write(
                        bytes,
                        offset,
                        bytes.size - offset
                    )
                    lastGain = nextGain
                    from = to
                }
                line.drain()
            } finally {
                line.stop()
                line.close()
            }
        }
    }

    override fun close() {}

    private companion object {
        const val CHUNK_SAMPLES = 2_048
        const val BUFFER_CHUNKS = 4
    }
}

/**
 * [samples] da [from] a [to] (escluso) in PCM little-endian, col guadagno che passa in linea retta
 * da [startGain] a [endGain]: un cambio di volume non fa scalino (e quindi clic).
 */
internal fun pcmBytes(
    samples: ShortArray,
    from: Int,
    to: Int,
    startGain: Float,
    endGain: Float
): ByteArray {
    val count = to - from
    val bytes = ByteArray(count * 2)
    for (i in 0 until count) {
        val g = startGain + (endGain - startGain) * i / count
        val s = (samples[from + i] * g).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[2 * i] = s.toByte()
        bytes[2 * i + 1] = (s shr 8).toByte()
    }
    return bytes
}
