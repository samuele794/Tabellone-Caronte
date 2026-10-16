package com.takaotech.departureboard.tts

import javazoom.jl.player.JavaSoundAudioDevice
import javazoom.jl.player.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayInputStream

/** MP3 su desktop con JLayer: `javax.sound` da solo non decodifica gli MP3. */
class JvmSoundPlayer : SoundPlayer {

    override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) =
        coroutineScope {
            val player =
                Player(ByteArrayInputStream(bytes), ScaledAudioDevice { volume })
        val playing = async(Dispatchers.IO) {
            // JLayer non avvisa quando suona: decodifica e suona un frame alla volta, quindi parte subito.
            onStart()
            player.play()
        }
        try {
            playing.await()
        } finally {
            // play() non risponde agli interrupt: chiudere il player è l'unico modo di fermarlo.
            player.close()
        }
    }

    override fun close() {}
}

/**
 * Uscita di JLayer che moltiplica i campioni per [gain] (0..1): JLayer da solo non ha un volume.
 * Il guadagno si rilegge a ogni frame decodificato e, se cambia, passa in rampa lungo il frame,
 * così non fa scalino (e quindi clic).
 */
internal class ScaledAudioDevice(private val gain: () -> Float) : JavaSoundAudioDevice() {
    private var last = Float.NaN

    override fun writeImpl(samples: ShortArray, offs: Int, len: Int) {
        val target = gain().coerceIn(0f, 1f)
        val start = if (last.isNaN()) target else last
        last = target
        if (start < 1f || target < 1f) {
            for (i in 0 until len) {
                val g = start + (target - start) * i / len
                samples[offs + i] = (samples[offs + i] * g).toInt().toShort()
            }
        }
        super.writeImpl(samples, offs, len)
    }
}
