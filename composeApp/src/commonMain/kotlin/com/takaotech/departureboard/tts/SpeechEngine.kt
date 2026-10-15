package com.takaotech.departureboard.tts

/**
 * Sintesi vocale. Ogni piattaforma fornisce il proprio motore (TTS di Android, voci del sistema
 * operativo su desktop…); [com.takaotech.departureboard.announce.Announcer] lo usa solo attraverso questa interfaccia.
 */
interface SpeechEngine : AutoCloseable {
    /**
     * Legge [text] in italiano al [volume] (0..1, rispetto a quello del dispositivo) e ritorna
     * quando la lettura è finita; cancellare la interrompe. [onStart] si chiama una volta, quando
     * la voce comincia a sentirsi (non se non parte mai).
     */
    suspend fun speak(text: String, volume: Float = 1f, onStart: () -> Unit = {})
}
