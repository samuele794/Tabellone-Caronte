package com.takaotech.departureboard.tts

import kotlinx.coroutines.flow.StateFlow

/** Riproduzione in loop di una traccia lunga (il sottofondo di stazione). */
interface LoopPlayer : AutoCloseable {
    /**
     * Riproduce in loop la traccia [path] di `composeResources` (MP3) e non ritorna finché non
     * viene cancellata, se non per un errore. [gain] (0..1, rispetto al volume del dispositivo)
     * si segue anche a riproduzione iniziata.
     */
    suspend fun loop(path: String, gain: StateFlow<Float>)
}
