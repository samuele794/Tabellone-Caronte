package announce

/**
 * Come si leggono ad alta voce i dati del monitor RFI: numeri, orari, binari e nomi di località
 * scritti in maiuscolo e abbreviati. Funzioni pure, così si provano senza motore TTS.
 */
object SpokenItalian {

    private val UNITS = listOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
        "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici",
        "diciassette", "diciotto", "diciannove",
    )
    private val TENS = listOf(
        "venti",
        "trenta",
        "quaranta",
        "cinquanta",
        "sessanta",
        "settanta",
        "ottanta",
        "novanta"
    )

    /** 0..99 in lettere: 21 → "ventuno", 23 → "ventitré", 28 → "ventotto". */
    fun number(n: Int): String {
        require(n in 0..99) { "fuori intervallo: $n" }
        if (n < 20) return UNITS[n]
        val tens = TENS[n / 10 - 2]
        return when (val u = n % 10) {
            0 -> tens
            1, 8 -> tens.dropLast(1) + UNITS[u]
            3 -> tens + "tré"
            else -> tens + UNITS[u]
        }
    }

    /**
     * Numero del treno come prescrive il MAS: 2 cifre per intero (84 "ottantaquattro"), 3 cifre
     * una per una (753 "sette cinque tre"), 4 cifre a coppie (9448 "novantaquattro quarantotto"),
     * 5 cifre la prima coppia e poi una per una (12156 "dodici uno cinque sei"). Le lettere che
     * RFI aggiunge ai bus ("1247A") si compitano dopo le cifre.
     */
    fun trainNumber(number: String): String {
        val digits = number.trim().takeWhile { it.isDigit() }
        val rest = number.trim().drop(digits.length).filter { it.isLetterOrDigit() }
        val spoken = when (digits.length) {
            0 -> emptyList()
            1, 2 -> listOf(pair(digits))
            3 -> singles(digits)
            4 -> listOf(pair(digits.take(2)), pair(digits.drop(2)))
            5 -> listOf(pair(digits.take(2))) + singles(digits.drop(2))
            else -> singles(digits)
        }
        return (spoken + rest.map { if (it.isDigit()) UNITS[it.digitToInt()] else it.uppercase() })
            .joinToString(" ")
    }

    /** "05" → "zero cinque", "48" → "quarantotto". */
    private fun pair(digits: String): String =
        if (digits.length == 2 && digits[0] == '0') "zero " + UNITS[digits[1].digitToInt()]
        else number(digits.toInt())

    private fun singles(digits: String) = digits.map { UNITS[it.digitToInt()] }

    /** "07:40" → "sette e quaranta", "18:05" → "diciotto e zero cinque", "09:00" → "nove". */
    fun time(hhmm: String): String {
        val (h, m) = hhmm.trim().split(':', '.').mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 2 && it[0] in 0..23 && it[1] in 0..59 } ?: return hhmm
        val hour = if (h == 1) "una" else number(h)
        return when {
            m == 0 -> hour
            m < 10 -> "$hour e zero ${number(m)}"
            else -> "$hour e ${number(m)}"
        }
    }

    /** Minuti di ritardo in lettere (oltre 99 in cifre: li legge il motore). */
    fun minutes(n: Int): String = if (n in 0..99) number(n) else n.toString()

    /** "3" → "tre", "12 TR" → "dodici T R", "PE" → "P E". */
    fun platform(platform: String): String =
        platform.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { token ->
            when {
                token.all { it.isDigit() } && token.length <= 2 -> number(token.toInt())
                token.all { it.isDigit() } -> singles(token).joinToString(" ")
                else -> Regex("\\d+|\\D+").findAll(token).joinToString(" ") { part ->
                    val p = part.value
                    if (p.first().isDigit()) platform(p) else p.uppercase().toList()
                        .joinToString(" ")
                }
            }
        }

    private val ABBREVIATIONS = mapOf(
        "C.LE" to "Centrale",
        "P.TA" to "Porta",
        "P.LE" to "Piazzale",
        "P.ZZA" to "Piazza",
        "LIM." to "Limito",
        "S.M.N." to "Santa Maria Novella",
        "STAZ." to "Stazione",
    )

    /** Nomi in -A che restano maschili: "S.NICOLA" è San Nicola. */
    private val MASCULINE_A = setOf("NICOLA", "LUCA", "BARNABA", "MATTIA", "ANDREA", "ELIA", "ENEA")

    /**
     * Nome di località per la voce: minuscolo con iniziali maiuscole (i motori leggono le parole
     * tutte maiuscole come sigle) e abbreviazioni RFI sciolte: "BARI C.LE" → "Bari Centrale",
     * "TORINO P.NUOVA" → "Torino Porta Nuova", "S.MARIA" → "Santa Maria".
     */
    fun place(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.split('-').joinToString("-", transform = ::placeWord)
            }

    private fun placeWord(word: String): String {
        val upper = word.uppercase()
        ABBREVIATIONS[upper]?.let { return it }
        // "P.NUOVA", "S.GIOVANNI", "S.ANNA": prefisso abbreviato attaccato al nome.
        Regex("^([PS])\\.(\\p{L}.*)$").find(upper)?.let { match ->
            val (prefix, rest) = match.destructured
            val spokenRest = placeWord(rest)
            return when {
                prefix == "P" -> "Porta $spokenRest"
                rest.first() in "AEIOU" -> "Sant'$spokenRest"
                rest.endsWith("A") && rest !in MASCULINE_A -> "Santa $spokenRest"
                rest.length > 1 && rest[0] == 'S' && rest[1] !in "AEIOU" -> "Santo $spokenRest"
                else -> "San $spokenRest"
            }
        }
        return capitalized(word)
    }

    /** "D'ADDA" → "D'Adda", "ROGOREDO" → "Rogoredo". */
    private fun capitalized(word: String): String {
        val lower = word.lowercase()
        return buildString {
            lower.forEachIndexed { i, c ->
                append(if (i == 0 || lower[i - 1] == '\'') c.uppercaseChar() else c)
            }
        }
    }

    private val CATEGORIES = mapOf(
        "ALTA VELOCITA'" to "Alta Velocità",
        "ALTA VELOCITA" to "Alta Velocità",
        "INTERCITY" to "Intercity",
        "INTERCITY NOTTE" to "Intercity Notte",
        "REG" to "Regionale",
        "REGIONALE" to "Regionale",
        "REGIONALE VELOCE" to "Regionale Veloce",
        "EUROCITY" to "Eurocity",
        "EURONIGHT" to "Euronight",
        "ES*" to "Eurostar",
        "BUS" to "Bus",
    )

    /** Categoria commerciale per esteso: "ALTA VELOCITA'" → "Alta Velocità", "REG" → "Regionale". */
    fun category(category: String): String {
        val key = category.trim().uppercase()
        return CATEGORIES[key] ?: place(key.filter { it.isLetterOrDigit() || it == ' ' })
    }

    /** Impresa ferroviaria: "TRENITALIA" → "Trenitalia"; le sigle brevi restano maiuscole. */
    fun carrier(carrier: String): String =
        carrier.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { if (it.length <= 4) it.uppercase() else capitalized(it) }

    /**
     * Fermate dalla colonna informazioni del monitor, senza gli orari:
     * "FERMA A:MILANO LAMBRATE (0:19) - PIOLTELLO LIM. (0:36)" → ["Milano Lambrate", "Pioltello Limito"].
     */
    fun stops(info: String): List<String> {
        val list = info.substringAfter("FERMA A:", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() } ?: return emptyList()
        return list.split(" - ")
            .map { it.replace(Regex("\\(\\s*\\d{1,2}:\\d{2}\\s*\\)"), "").trim() }
            .filter { it.isNotEmpty() }
            .map(::place)
    }
}
