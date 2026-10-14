package tts

/**
 * Sintesi vocale. Ogni piattaforma fornisce il proprio motore (TTS di Android, voci del sistema
 * operativo su desktop…); [announce.Announcer] lo usa solo attraverso questa interfaccia.
 */
interface SpeechEngine : AutoCloseable {
    /** Legge [text] in italiano e ritorna quando la lettura è finita; cancellare la interrompe. */
    suspend fun speak(text: String)
}
