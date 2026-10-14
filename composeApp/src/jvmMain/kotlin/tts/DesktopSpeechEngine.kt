package tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

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

    override suspend fun speak(text: String) {
        val (command, stdin) = withContext(Dispatchers.IO) { command(text) }
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
            }
            // Cancellare la coroutine interrompe l'attesa e il finally chiude il processo.
            val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
            check(exit == 0) { "${command.first()} è uscito con codice $exit" }
        } finally {
            process.destroy()
        }
    }

    /** Comando da lanciare e, se serve, il testo da passargli su stdin. */
    private fun command(text: String): Pair<List<String>, String?> = when {
        "mac" in os -> {
            val v = macVoice
            (listOf("say") + (if (v != null) listOf("-v", v) else emptyList()) + text) to null
        }

        "win" in os -> listOf(
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            WINDOWS_SCRIPT
        ) to text

        onPath("espeak-ng") -> listOf("espeak-ng", "-v", voice ?: "it", text) to null
        onPath("spd-say") -> listOf("spd-say", "-w", "-l", "it", text) to null
        else -> error("Nessun motore TTS trovato: installa espeak-ng (o speech-dispatcher)")
    }

    private fun onPath(name: String) =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, name).let { f -> f.isFile && f.canExecute() } }

    override fun close() {}

    private companion object {
        /** Legge il testo da stdin (in UTF-8, per le lettere accentate) con la prima voce it-*. */
        val WINDOWS_SCRIPT = """
            [Console]::InputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}v = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -like 'it*' } | Select-Object -First 1
            if (${'$'}v) { ${'$'}s.SelectVoice(${'$'}v.VoiceInfo.Name) }
            ${'$'}s.Speak([Console]::In.ReadToEnd())
        """.trimIndent()
    }
}
