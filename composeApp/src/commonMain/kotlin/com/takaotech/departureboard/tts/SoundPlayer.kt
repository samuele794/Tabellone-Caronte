package com.takaotech.departureboard.tts

/** Riproduzione di un file audio (il jingle che precede gli annunci). */
interface SoundPlayer : AutoCloseable {
    /** Riproduce [bytes] (MP3) e ritorna a fine riproduzione; cancellare la interrompe. */
    suspend fun play(bytes: ByteArray)
}
