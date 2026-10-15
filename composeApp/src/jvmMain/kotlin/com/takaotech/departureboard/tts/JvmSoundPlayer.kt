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
                Player(ByteArrayInputStream(bytes), ScaledAudioDevice(volume.coerceIn(0f, 1f)))
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

/** Uscita di JLayer che moltiplica i campioni per [volume]: JLayer da solo non ha un volume. */
internal class ScaledAudioDevice(private val volume: Float) : JavaSoundAudioDevice() {
    override fun writeImpl(samples: ShortArray, offs: Int, len: Int) {
        if (volume < 1f) {
            for (i in offs until offs + len) samples[i] = (samples[i] * volume).toInt().toShort()
        }
        super.writeImpl(samples, offs, len)
    }
}
