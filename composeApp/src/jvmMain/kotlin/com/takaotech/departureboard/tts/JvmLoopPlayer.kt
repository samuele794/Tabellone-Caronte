package com.takaotech.departureboard.tts

import com.takaotech.departureboard.resources.Res
import javazoom.jl.player.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * MP3 in loop su desktop con JLayer, letto a pezzi dalle risorse (nel jar) invece che tutto in
 * memoria. A ogni giro il file si riapre: tra la fine e l'inizio resta una pausa brevissima.
 */
class JvmLoopPlayer : LoopPlayer {

    override suspend fun loop(path: String, gain: StateFlow<Float>) {
        val uri = URI(Res.getUri(path))
        while (true) playOnce(uri, gain)
    }

    private suspend fun playOnce(uri: URI, gain: StateFlow<Float>) = coroutineScope {
        val stream = withContext(Dispatchers.IO) { uri.toURL().openStream().buffered() }
        val player = Player(stream, ScaledAudioDevice { gain.value })
        val playing = async(Dispatchers.IO) { player.play() }
        try {
            playing.await()
            // Un file vuoto o illeggibile finirebbe subito: senza questo controllo girerebbe a vuoto.
            check(player.position > 0) { "Traccia vuota: $uri" }
        } finally {
            // play() non risponde agli interrupt: chiudere il player è l'unico modo di fermarlo.
            player.close()
            stream.close()
        }
    }

    override fun close() {}
}
