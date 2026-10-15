package com.takaotech.departureboard.tts

/** Riproduzione di un file audio (il jingle che precede gli annunci). */
interface SoundPlayer : AutoCloseable {
    /**
     * Riproduce [bytes] (MP3) al [volume] (0..1, rispetto a quello del dispositivo) e ritorna a
     * fine riproduzione; cancellare la interrompe. [onStart] si chiama una volta, quando il suono
     * comincia a sentirsi (non se non parte mai).
     */
    suspend fun play(bytes: ByteArray, volume: Float = 1f, onStart: () -> Unit = {})
}
