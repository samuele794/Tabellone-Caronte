package com.takaotech.departureboard.intro

import com.takaotech.departureboard.intro.IntroTimeline.TOTAL
import com.takaotech.departureboard.rfi.Station
import kotlin.math.floor
import kotlin.random.Random

/**
 * Fasi dell'intro, in ordine: terminale, stacco a bande, cruscotto col tabellone, banner
 * "TABELLONE ATTIVATO", tabellone che si allarga a tutto schermo, fine.
 */
enum class IntroPhase { Terminal, Glitch, Dashboard, Banner, Zoom, Done }

/**
 * Tempi dell'intro in millisecondi dall'inizio dell'audio (`files/intro.mp3`, lungo [TOTAL]).
 * Le battute vengono dalla traccia: colpo a 37,5 s, stinger a 41,25 s, sfumatura da 42,5 s.
 */
object IntroTimeline {
    const val TRACK = "files/intro.mp3"

    /**
     * Cruscotto e tabellone si disegnano già sotto il terminale, che li copre: il primo disegno
     * costa, e durante lo stacco farebbe saltare le bande.
     */
    const val WARMUP_AT = 36_500L

    /** Colpo dell'audio: il cruscotto comincia a entrare a bande sopra il terminale. */
    const val GLITCH_AT = 37_500L

    /** Il terminale è sparito: si vede solo il cruscotto. */
    const val DASHBOARD_AT = 37_850L

    /** Stinger: si apre il banner. */
    const val BANNER_AT = 41_250L

    /** Il banner comincia a chiudersi. */
    const val BANNER_OUT_AT = 43_800L

    /** Durata della chiusura del banner. */
    const val BANNER_OUT = 500L

    /** Il cruscotto esce e il tabellone si allarga a tutto schermo, fino a [TOTAL]. */
    const val ZOOM_AT = 44_000L

    /** Fine dell'audio e dell'intro. */
    const val TOTAL = 45_290L

    /** Da qui compare il pulsante per saltare l'intro (prima non ci si arriva per sbaglio col gesto che la avvia sul web). */
    const val SKIP_ARMED_AT = 600L

    /** Dissolvenza dell'intro saltata. */
    const val SKIP_FADE = 400L

    fun phaseAt(millis: Long): IntroPhase = when {
        millis < GLITCH_AT -> IntroPhase.Terminal
        millis < DASHBOARD_AT -> IntroPhase.Glitch
        millis < BANNER_AT -> IntroPhase.Dashboard
        millis < ZOOM_AT -> IntroPhase.Banner
        millis < TOTAL -> IntroPhase.Zoom
        else -> IntroPhase.Done
    }

    /** Avanzamento (0..1) di [millis] nell'intervallo da [from] lungo [length]. */
    fun progress(millis: Long, from: Long, length: Long): Float =
        ((millis - from).toFloat() / length).coerceIn(0f, 1f)
}

/** Aspetto di una riga della console. */
enum class LineKind { Command, Output, Error, Net }

/** Una riga visibile nella console; [gap] = riga vuota prima, come tra i blocchi del video. */
data class ConsoleLine(val text: String, val kind: LineKind, val gap: Boolean = false)

/**
 * Evento del copione. Una riga [LineKind.Command] mostra subito [prompt] e poi batte [text] un
 * carattere alla volta da [typeAt]; le altre righe compaiono intere. [clear] svuota la console
 * (il comando PULISCI) prima della riga, se c'è.
 */
internal data class ConsoleEvent(
    val at: Long,
    val text: String = "",
    val kind: LineKind = LineKind.Output,
    val prompt: String = "",
    val typeAt: Long = at,
    val gap: Boolean = false,
    val clear: Boolean = false,
)

/** Caratteri al secondo dei comandi battuti. */
internal const val TYPE_CPS = 18f

/** Sigla della stazione nei prompt: maiuscole, spazi e simboli in un solo "_", al più 18 caratteri. */
internal fun promptName(station: Station): String =
    station.name.uppercase().split { !it.isLetterOrDigit() }.filter { it.isNotEmpty() }
        .joinToString("_").take(18).trimEnd('_').ifEmpty { "STAZIONE" }

private fun String.split(separator: (Char) -> Boolean): List<String> {
    val parts = mutableListOf(StringBuilder())
    for (c in this) if (separator(c)) parts += StringBuilder() else parts.last().append(c)
    return parts.map { it.toString() }
}

/** Iniziali delle prime due parole del nome ("MILANO CENTRALE" → "MC"), o le prime due lettere. */
internal fun stationInitials(station: Station): String {
    val words = station.name.uppercase().split(' ', '.', '-', '\'').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "ST"
    }
}

/** Millisecondi per battere [text] a [TYPE_CPS]. */
private fun typingMillis(text: String): Long = (text.length * 1000f / TYPE_CPS).toLong()

/**
 * Copione della console: la sala circolazione si autentica, prova una stazione che non risponde
 * ([decoy], presa a caso dalla cache) e una che non esiste, poi si collega a [station] (quella
 * scelta nel tabellone). Senza [decoy] la prima prova è la stazione 81 del compartimento MI.
 */
internal fun consoleScript(station: Station, decoy: Station? = null): List<ConsoleEvent> {
    val name = station.name.uppercase()
    val prompt = "\$${promptName(station)}//> "
    val cmd = LineKind.Command
    val err = LineKind.Error
    val net = LineKind.Net
    val keyLines = privateKeyLines()
    return buildList {
        add(ConsoleEvent(600, "SCC", cmd, prompt = "\$//> "))
        add(ConsoleEvent(1_700, "CONNETTI", cmd, prompt = "\$SCC//> "))
        add(ConsoleEvent(2_300, "[AUT] AUTENTICAZIONE RICHIESTA"))
        add(ConsoleEvent(3_000, "AUT", cmd, prompt = "\$SCC//> ", gap = true))
        add(
            ConsoleEvent(
                3_900, "0F51839-047A5-0F51905-E1B5F0-0FS1839", cmd,
                prompt = "CHIAVE: ", gap = true,
            )
        )
        add(ConsoleEvent(6_500, "[AUT] VERIFICA..", gap = true))
        add(ConsoleEvent(6_900, "[AUT] VERIFICA.."))
        add(ConsoleEvent(7_300, "[AUT] VERIFICA.."))
        add(ConsoleEvent(7_600, "[AUT] OK"))
        add(ConsoleEvent(7_800, "[SCC] COMANDI DISPONIBILI:", gap = true))
        add(
            ConsoleEvent(
                7_800,
                "CONNETTI | AUT | STATO | PING | STAZIONE | INGRESSO | USCITA | DEVIATOI | ESCI"
            )
        )
        add(
            ConsoleEvent(
                7_800,
                "TRACCIA | FORZA | ARRESTO | REGISTRO | CIFRA | ALLARME | SGOMBRA | AIUTO | PIC"
            )
        )
        add(ConsoleEvent(7_950, "PULISCI", cmd, prompt = "\$SCC//> ", gap = true))

        add(ConsoleEvent(8_800, "STAZIONE", cmd, prompt = "\$//> ", clear = true))
        add(ConsoleEvent(9_400, "[STAZIONE] PARAMETRO /H NON VALIDO PER I COMANDI"))
        add(ConsoleEvent(9_900, "/H", cmd, prompt = "\$SCC//> ", gap = true))
        add(ConsoleEvent(10_600, "[ERR] \"/H\" NON È UN COMANDO INTERNO O ESTERNO", err))
        add(ConsoleEvent(11_600, "STAZIONE /H", cmd, prompt = "\$SCC//> ", gap = true))
        add(ConsoleEvent(12_600, "[STAZIONE] COMANDI DISPONIBILI:"))
        val selectArgs = if (decoy == null) "[COMPARTIMENTO,CODICE]" else "[CODICE]"
        add(
            ConsoleEvent(
                12_600,
                "SELEZIONA $selectArgs | GRUPPO | PING | TRACCIA | FORZA | ARRESTO | SGOMBRA | AIUTO"
            )
        )

        val decoyId = decoy?.id ?: "MI 81"
        val decoyPrompt = "\$${if (decoy == null) "STAZIONE_MI_81" else promptName(decoy)}//> "
        val decoyCommand = "STAZIONE SELEZIONA $decoyId"
        // La risposta arriva dopo l'ultimo carattere, anche con i codici lunghi (FNM).
        val decoySelected = maxOf(14_500L, 13_300L + typingMillis(decoyCommand) + 150)
        add(ConsoleEvent(13_300, decoyCommand, cmd, prompt = "\$//> ", gap = true))
        add(
            ConsoleEvent(
                decoySelected,
                if (decoy == null) "[SIS] COMPARTIMENTO MI STAZIONE 81 SELEZIONATA"
                else "[SIS] STAZIONE ${decoy.id} ${decoy.name.uppercase()} SELEZIONATA",
            )
        )
        add(ConsoleEvent(decoySelected + 200, "", cmd, prompt = decoyPrompt, gap = true))

        add(
            ConsoleEvent(
                15_900,
                if (decoy == null) "[SIS] CONNESSIONE A COMPARTIMENTO MI STAZIONE 81"
                else "[SIS] CONNESSIONE A ${decoy.name.uppercase()}",
                clear = true,
            )
        )
        for (at in listOf(16_900L, 17_900L, 18_900L, 19_900L)) {
            add(ConsoleEvent(at, "[STAZIONE] CONNESSIONE..."))
        }
        add(ConsoleEvent(21_000, "[STAZIONE] TEMPO DI CONNESSIONE SCADUTO", err))
        add(ConsoleEvent(21_300, "[STAZIONE] LA STAZIONE NON RISPONDE", err))
        add(ConsoleEvent(22_000, "^C", cmd, prompt = decoyPrompt, gap = true))
        add(ConsoleEvent(23_000, "STAZIONE SELEZIONA B2", cmd, prompt = "\$//> ", typeAt = 24_400))
        add(
            ConsoleEvent(
                25_900,
                if (decoy == null) "[ERR] COMPARTIMENTO \"B2\" INESISTENTE" else "[ERR] STAZIONE \"B2\" INESISTENTE",
                err,
            )
        )
        val command = "STAZIONE SELEZIONA ${station.id}"
        add(ConsoleEvent(26_300, command, cmd, prompt = "\$//> ", typeAt = 26_700))
        val selected = maxOf(28_000L, 26_700L + typingMillis(command) + 150)
        add(ConsoleEvent(selected, "[SIS] STAZIONE ${station.id} $name SELEZIONATA"))
        add(ConsoleEvent(selected + 200, "", cmd, prompt = prompt, gap = true))

        add(ConsoleEvent(29_000, "[SIS] CONNESSIONE A $name", clear = true))
        add(ConsoleEvent(30_000, "[STAZIONE] CONNESSIONE..."))

        add(ConsoleEvent(31_000, "[RETE] -----INIZIO CHIAVE PRIVATA SCC-----", net, clear = true))
        keyLines.forEachIndexed { i, line -> add(ConsoleEvent(31_250L + i * 175L, line, net)) }
        add(ConsoleEvent(35_900, "[RETE] HANDSHAKE COMPLETATO", net, clear = true))
        add(ConsoleEvent(36_600, "[STAZIONE] CONNESSA"))
        add(ConsoleEvent(37_000, "", cmd, prompt = prompt, gap = true))
    }
}

/** Righe di chiave "privata", sempre le stesse: tre gruppi di tre blocchi da cinque caratteri. */
private fun privateKeyLines(): List<String> {
    val random = Random(1839)
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
    fun block() = (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("")
    fun group() = "${block()}-${block()}-${block()}"
    return List(26) { "[RETE] ${group()} ${group()} ${group()}" }
}

/** Righe visibili nella console a [millis], dalla più vecchia; i comandi sono battuti fin lì. */
fun consoleAt(millis: Long, station: Station, decoy: Station? = null): List<ConsoleLine> =
    consoleAt(millis, consoleScript(station, decoy))

internal fun consoleAt(millis: Long, script: List<ConsoleEvent>): List<ConsoleLine> {
    val lines = ArrayList<ConsoleLine>()
    for (event in script) {
        if (event.at > millis) break
        if (event.clear) lines.clear()
        val text = if (event.kind == LineKind.Command) {
            val typed = floor((millis - event.typeAt).coerceAtLeast(0) * TYPE_CPS / 1000f).toInt()
            event.prompt + event.text.take(typed)
        } else {
            event.text
        }
        lines += ConsoleLine(text, event.kind, gap = event.gap && lines.isNotEmpty())
    }
    return lines
}
