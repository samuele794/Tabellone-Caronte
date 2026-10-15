package com.takaotech.departureboard.tts

import javazoom.jl.player.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayInputStream

/** MP3 su desktop con JLayer: `javax.sound` da solo non decodifica gli MP3. */
class JvmSoundPlayer : SoundPlayer {

    override suspend fun play(bytes: ByteArray, onStart: () -> Unit) = coroutineScope {
        val player = Player(ByteArrayInputStream(bytes))
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
