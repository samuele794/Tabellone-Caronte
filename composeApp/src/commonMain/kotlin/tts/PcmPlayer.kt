package tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Riproduzione di suoni generati in codice (il crepitio delle palette). */
interface PcmPlayer : AutoCloseable {
    /**
     * Riproduce [samples] (PCM mono a 16 bit, [sampleRate] campioni al secondo) e ritorna a fine
     * riproduzione; cancellare la interrompe. Più riproduzioni possono sovrapporsi.
     *
     * [gain] (0..1) moltiplica il suono e si segue anche a riproduzione iniziata, con un ritardo
     * di qualche decina di millisecondi.
     */
    suspend fun play(
        samples: ShortArray,
        sampleRate: Int,
        gain: StateFlow<Float> = MutableStateFlow(1f)
    )
}
