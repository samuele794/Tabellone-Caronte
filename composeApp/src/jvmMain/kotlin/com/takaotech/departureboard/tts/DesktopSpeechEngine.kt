package com.takaotech.departureboard.tts

import com.takaotech.departureboard.tts.DesktopSpeechEngine.Companion.VOLUME_ENV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Sintesi vocale del sistema operativo, senza librerie: `say` su macOS, `System.Speech` via
 * PowerShell su Windows, `espeak-ng` (o `spd-say`) su Linux. Serve una voce italiana installata
 * (macOS: Alice/Federica/Luca; Windows: una voce it-IT; Linux: il pacchetto espeak-ng).
 *
 * [voice] forza una voce per nome; null = la prima voce italiana trovata.
 */
class DesktopSpeechEngine(private val voice: String? = null) : SpeechEngine {

    private val os = System.getProperty("os.name").orEmpty().lowercase()

    /** Voce italiana di `say`, cercata una volta sola. */
    private val macVoice by lazy {
        voice ?: runCatching {
            // "Alice               it_IT    # Ciao! Mi chiamo Alice."
            val line = Regex("^(.+?)\\s+it[_-]IT\\s")
            ProcessBuilder("say", "-v", "?").start().inputStream.bufferedReader().readLines()
                .firstNotNullOfOrNull { line.find(it)?.groupValues?.get(1)?.trim() }
        }.getOrNull()
    }

    override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) {
        val percent = (volume.coerceIn(0f, 1f) * 100).roundToInt()
        if ("mac" in os) return speakMac(text, percent, onStart)
        val (command, stdin) = command(text, percent)
        // I comandi non dicono quando comincia l'audio: si parte da quando hanno il testo.
        run(command, stdin, env = mapOf(VOLUME_ENV to "$percent"), onFed = onStart)
    }

    /**
     * `say` non ha un volume (il comando incorporato `[[volm]]` non cambia l'audio che produce
     * con le voci attuali): sintetizza su un file, poi `afplay -v` lo riproduce al volume
     * [percent], così [onStart] arriva anche quando la voce comincia davvero.
     */
    private suspend fun speakMac(text: String, percent: Int, onStart: () -> Unit) {
        val file = withContext(Dispatchers.IO) { File.createTempFile("annuncio", ".aiff") }
        try {
            val v = withContext(Dispatchers.IO) { macVoice }
            run(
                listOf("say") + (if (v != null) listOf("-v", v) else emptyList()) + listOf(
                    "-o",
                    file.path,
                    text
                )
            )
            onStart()
            // Volume lineare, 1 = quello del sistema; col punto, non la virgola.
            val level = "${percent / 100}.${(percent % 100).toString().padStart(2, '0')}"
            run(listOf("afplay", "-v", level, file.path))
        } finally {
            file.delete()
        }
    }

    /**
     * Lancia [command] con le variabili [env], gli passa [stdin] e ne attende la fine; [onFed]
     * si chiama appena il comando ha ricevuto il testo. Cancellare la coroutine chiude il processo.
     */
    private suspend fun run(
        command: List<String>,
        stdin: String? = null,
        env: Map<String, String> = emptyMap(),
        onFed: () -> Unit = {},
    ) {
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .apply { environment().putAll(env) }
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
            }
            onFed()
            val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
            check(exit == 0) { "${command.first()} è uscito con codice $exit" }
        } finally {
            process.destroy()
        }
    }

    /**
     * Comando da lanciare (Windows e Linux) e, se serve, il testo da passargli su stdin, al
     * volume [percent] (0..100). Su Windows il volume passa dalla variabile d'ambiente [VOLUME_ENV].
     */
    private fun command(text: String, percent: Int): Pair<List<String>, String?> = when {
        "win" in os -> listOf(
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            WINDOWS_SCRIPT
        ) to text

        // espeak-ng: ampiezza 0..200, 100 di serie; spd-say: volume -100..100, 0 di serie.
        onPath("espeak-ng") -> listOf(
            "espeak-ng",
            "-v",
            voice ?: "it",
            "-a",
            "$percent",
            text
        ) to null

        onPath("spd-say") -> listOf(
            "spd-say",
            "-w",
            "-l",
            "it",
            "-i",
            "${percent - 100}",
            text
        ) to null
        else -> error("Nessun motore TTS trovato: installa espeak-ng (o speech-dispatcher)")
    }

    private fun onPath(name: String) =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, name).let { f -> f.isFile && f.canExecute() } }

    override fun close() {}

    private companion object {
        const val VOLUME_ENV = "DEPARTURE_BOARD_VOLUME"

        /**
         * Legge il testo da stdin (in UTF-8, per le lettere accentate) con la prima voce it-*, al
         * volume (0..100) della variabile d'ambiente [VOLUME_ENV].
         */
        val WINDOWS_SCRIPT = """
            [Console]::InputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}v = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -like 'it*' } | Select-Object -First 1
            if (${'$'}v) { ${'$'}s.SelectVoice(${'$'}v.VoiceInfo.Name) }
            ${'$'}s.Volume = [int]${'$'}env:$VOLUME_ENV
            ${'$'}s.Speak([Console]::In.ReadToEnd())
        """.trimIndent()
    }
}
