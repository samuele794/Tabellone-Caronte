package com.takaotech.departureboard.announce

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

    /** Numero qualsiasi (fino a 999 999) in lettere: 261 → "duecentosessantuno", 1000 → "mille". */
    fun cardinal(n: Int): String {
        require(n in 0..999_999) { "fuori intervallo: $n" }
        if (n < 100) return number(n)
        if (n < 1000) {
            val hundreds = if (n / 100 == 1) "cento" else UNITS[n / 100] + "cento"
            val rest = n % 100
            // "centottanta": la "o" di cento cade davanti a otto/ottanta.
            return when {
                rest == 0 -> hundreds
                rest / 10 == 8 || rest == 8 -> hundreds.dropLast(1) + number(rest)
                else -> hundreds + number(rest)
            }
        }
        val thousands = if (n / 1000 == 1) "mille" else cardinal(n / 1000) + "mila"
        val rest = n % 1000
        return if (rest == 0) thousands else thousands + cardinal(rest)
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
        return (spoken + rest.map { if (it.isDigit()) UNITS[it.digitToInt()] else letter(it) })
            .joinToString(" ")
    }

    /** "05" → "zero cinque", "48" → "quarantotto". */
    private fun pair(digits: String): String =
        if (digits.length == 2 && digits[0] == '0') "zero " + UNITS[digits[1].digitToInt()]
        else number(digits.toInt())

    private fun singles(digits: String) = digits.map { UNITS[it.digitToInt()] }

    private val LETTERS = mapOf(
        'A' to "a",
        'B' to "bi",
        'C' to "ci",
        'D' to "di",
        'E' to "e",
        'F' to "effe",
        'G' to "gi",
        'H' to "acca",
        'I' to "i",
        'J' to "i lunga",
        'K' to "cappa",
        'L' to "elle",
        'M' to "emme",
        'N' to "enne",
        'O' to "o",
        'P' to "pi",
        'Q' to "cu",
        'R' to "erre",
        'S' to "esse",
        'T' to "ti",
        'U' to "u",
        'V' to "vu",
        'W' to "doppia vu",
        'X' to "ics",
        'Y' to "ipsilon",
        'Z' to "zeta",
    )

    /** Nome italiano della lettera, così il motore non deve indovinare: 'S' → "esse". */
    fun letter(c: Char): String = LETTERS[c.uppercaseChar()] ?: c.toString()

    /** Sigla compitata: "FM3" → "effe emme tre", "S13" → "esse tredici". */
    fun spell(code: String): String =
        Regex("\\d+|\\p{L}").findAll(code.uppercase()).joinToString(" ") { part ->
            val p = part.value
            if (p.first()
                    .isDigit()
            ) (if (p.length <= 2) number(p.toInt()) else singles(p).joinToString(" "))
            else letter(p.first())
        }

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

    private val PLATFORM_WORDS = mapOf(
        "OVEST" to "ovest", "EST" to "est", "NORD" to "nord", "SUD" to "sud",
        "TR" to "tronco", "SOT" to "sotterraneo",
    )

    /**
     * "3" → "tre", "11TR" → "undici tronco", "1-OVEST" → "uno ovest", "1 SOT" → "uno sotterraneo",
     * "20B" → "venti bi"; le sigle sconosciute si compitano ("PE" → "pi e").
     */
    fun platform(platform: String): String =
        Regex("\\d+|\\p{L}+").findAll(platform.uppercase()).joinToString(" ") { part ->
            val p = part.value
            when {
                p.first()
                    .isDigit() -> if (p.length <= 2) number(p.toInt()) else singles(p).joinToString(
                    " "
                )

                else -> PLATFORM_WORDS[p] ?: spell(p)
            }
        }

    private val ABBREVIATIONS = mapOf(
        "C.LE" to "Centrale",
        "CENTR." to "Centrale",
        "CENT." to "Centrale",
        "P.TA" to "Porta",
        "P.LE" to "Piazzale",
        "P.ZZA" to "Piazza",
        "IND.LE" to "Industriale",
        "LIM." to "Limito",
        "S.M.N." to "Santa Maria Novella",
        "SMN" to "Santa Maria Novella",
        "STAZ." to "Stazione",
        "AV" to "Alta Velocità",
        "HBF" to "Hauptbahnhof",
    )

    /** Nomi interi che nessuna regola ricostruisce. */
    private val PLACES = mapOf(
        "FIRENZE CM" to "Firenze Campo di Marte",
        "REGGIO E. AV MP." to "Reggio Emilia Alta Velocità Mediopadana",
    )

    /** Sigle di città in testa al nome delle fermate urbane: "MI.P.GARIBALDI", "VE MESTRE", "PA BRANCACCIO". */
    private val CITIES = mapOf(
        "MI" to "Milano", "BO" to "Bologna", "GE" to "Genova", "FI" to "Firenze", "PA" to "Palermo",
        "TO" to "Torino", "BA" to "Bari", "VE" to "Venezia", "PG" to "Perugia", "NA" to "Napoli",
        "RM" to "Roma",
    )

    /** Nomi in -A che restano maschili: "S.NICOLA" è San Nicola. */
    private val MASCULINE_A = setOf("NICOLA", "LUCA", "BARNABA", "MATTIA", "ANDREA", "ELIA", "ENEA")

    /** Nomi femminili che non finiscono in -A: "S.CROCE" è Santa Croce. */
    private val FEMININE = setOf("CROCE", "AGNESE", "BEATRICE", "FELICITE")

    /** Preposizioni e articoli dentro i nomi, in minuscolo: "Isola della Scala", "Bari Torre a Mare". */
    private val MINOR_WORDS = setOf(
        "A", "AD", "AL", "ALLA", "ALLE", "DA", "DAL", "DE", "DEI", "DEL", "DELLA", "DELLE", "DEGLI",
        "DI", "E", "IN", "SU", "SUL", "SULLA",
    )

    /**
     * Apostrofi come li scrive RFI: doppi ("D''ADDA") o rovesciati ("D`ADDA") diventano normali,
     * e la vocale finale seguita da apostrofo è un accento ("SANTHIA'" → "SANTHIÀ").
     */
    fun normalizeApostrophes(text: String): String =
        text.replace('`', '\'').replace(Regex("'{2,}"), "'")
            .replace(Regex("([AEIOUaeiou])'(?=\\s|$|[-.,)])")) { m ->
                val accented = mapOf('A' to 'À', 'E' to 'È', 'I' to 'Ì', 'O' to 'Ò', 'U' to 'Ù')
                val c = m.groupValues[1][0]
                (accented[c.uppercaseChar()]
                    ?: c).let { if (c.isLowerCase()) it.lowercaseChar() else it }.toString()
            }

    /**
     * Nome di località per la voce: minuscolo con iniziali maiuscole (i motori leggono le parole
     * tutte maiuscole come sigle) e abbreviazioni RFI sciolte: "BARI C.LE" → "Bari Centrale",
     * "MI.P.GARIBALDI" → "Milano Porta Garibaldi", "S.MARIA" → "Santa Maria".
     * Per le abbreviazioni che solo l'elenco delle stazioni può sciogliere c'è [PlaceNames].
     */
    fun place(name: String): String {
        val clean = normalizeApostrophes(name.trim()).replace(Regex("\\s+"), " ")
        PLACES[clean.uppercase()]?.let { return it }
        // "TORINO P. SUSA", "S. AMBROGIO": il prefisso abbreviato si attacca al nome che segue.
        val words = clean.replace(Regex("\\b([SP])\\. (?=\\p{L})"), "$1.").split(' ')
            .filter { it.isNotEmpty() }
        return words.mapIndexed { i, word ->
            if (i > 0 && word.uppercase() in MINOR_WORDS) word.lowercase()
            else word.split('-')
                .joinToString("-") { placeWord(it, first = i == 0, alone = words.size == 1) }
        }.joinToString(" ")
    }

    /** [first]: prima parola del nome, dove può esserci la sigla della città; [alone]: unica parola. */
    private fun placeWord(word: String, first: Boolean, alone: Boolean): String {
        val upper = word.uppercase()
        ABBREVIATIONS[upper]?.let { return it }
        if (first && !alone) CITIES[upper.removeSuffix(".")]?.let { return it }
        // "MI.P.GARIBALDI", "P.NUOVA", "S.GIOVANNI", "S.ANNA": prefissi abbreviati attaccati al nome.
        Regex("^(\\p{L}{1,2})\\.(\\p{L}.*)$").find(upper)?.let { match ->
            val (prefix, rest) = match.destructured
            val spokenRest = placeWord(rest, first = false, alone = false)
            // "P.G." (Parco Groane) non è "Porta": serve un nome intero dopo il punto.
            val fullWord = Regex("\\p{L}{3,}").matches(rest)
            when {
                first && prefix in CITIES -> return "${CITIES.getValue(prefix)} $spokenRest"
                prefix == "P" && fullWord -> return "Porta $spokenRest"
                prefix == "S" && rest.length > 1 -> return saint(rest, spokenRest)
            }
        }
        return capitalized(word)
    }

    /** "S." davanti a un nome: San Giovanni, Santa Maria, Sant'Anna, Santo Stefano. */
    private fun saint(name: String, spoken: String) = when {
        name.first() in "AEIOU" -> "Sant'$spoken"
        (name.endsWith("A") && name !in MASCULINE_A) || name in FEMININE -> "Santa $spoken"
        name.length > 1 && name[0] == 'S' && name[1] !in "AEIOU" -> "Santo $spoken"
        else -> "San $spoken"
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
        "AV" to "Alta Velocità",
        "INTERCITY" to "Intercity",
        "IC" to "Intercity",
        "INTERCITY NOTTE" to "Intercity Notte",
        "ICN" to "Intercity Notte",
        "REG" to "Regionale",
        "REGIONALE" to "Regionale",
        "R" to "Regionale",
        "REGIONALE VELOCE" to "Regionale Veloce",
        "RV" to "Regionale Veloce",
        "RE" to "RegioExpress",
        "EUROCITY" to "Eurocity",
        "EC" to "Eurocity",
        "EURONIGHT" to "Euronight",
        "EN" to "Euronight",
        "ES*" to "Eurostar",
        "BUS" to "Bus",
    )

    /**
     * Categoria commerciale per esteso: "ALTA VELOCITA'" → "Alta Velocità", "RV" → "Regionale
     * Veloce", le linee suburbane "S5" → "Suburbano esse cinque", altre sigle ("FM3") compitate.
     * Vuota se il monitor non la indica.
     */
    fun category(category: String): String {
        val key = category.trim().uppercase()
        CATEGORIES[key]?.let { return it }
        return when {
            key.isEmpty() -> ""
            Regex("S\\d{1,2}").matches(key) -> "Suburbano ${spell(key)}"
            key.length <= 4 || key.any { it.isDigit() } -> spell(key)
            else -> place(key.filter { it.isLetterOrDigit() || it == ' ' })
        }
    }

    /**
     * Nel campo dell'impresa RFI mette spesso il marchio commerciale: il MAS vuole l'impresa
     * ferroviaria ("di Trenitalia"), quindi i marchi si riconducono a chi gestisce il treno.
     */
    private val BRANDS = mapOf(
        "FRECCIAROSSA" to "Trenitalia",
        "FRECCIARGENTO" to "Trenitalia",
        "FRECCIABIANCA" to "Trenitalia",
        "INTERCITY" to "Trenitalia",
        "INTERCITY NOTTE" to "Trenitalia",
        "LEONARDO EXPRESS" to "Trenitalia",
        "NTV" to "Italo",
    )

    /** Impresa ferroviaria: "TRENITALIA" → "Trenitalia", "FRECCIAROSSA" → "Trenitalia", "NTV" → "Italo". */
    fun carrier(carrier: String): String {
        val key = carrier.trim().uppercase().replace(Regex("\\s+"), " ")
        return BRANDS[key] ?: key.split(' ').filter { it.isNotEmpty() }
            .joinToString(" ", transform = ::capitalized)
    }

    /**
     * Fermate dalla colonna informazioni del monitor, senza gli orari e ancora come le scrive RFI
     * (le legge [PlaceNames.spoken]):
     * "FERMA A:MILANO LAMBRATE (0:19) - PIOLTELLO LIM. (0:36)" → ["MILANO LAMBRATE", "PIOLTELLO LIM."].
     */
    fun stops(info: String): List<String> {
        val list = info.substringAfter("FERMA A:", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() } ?: return emptyList()
        return list.split(" - ")
            .map { it.replace(Regex("\\(\\s*\\d{1,2}:\\d{2}\\s*\\)"), "").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Frase libera del monitor (le note): minuscolo con la prima lettera maiuscola, orari e numeri
     * in lettere, sigle di una lettera compitate, abbreviazioni sciolte:
     * "VIAGGIATORI DA POTENZA C.LE CON BUS SOSTITUTIVO ALLE ORE 17:00" →
     * "Viaggiatori da potenza Centrale con bus sostitutivo alle ore diciassette".
     */
    fun sentence(text: String): String =
        normalizeApostrophes(text.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                val upper = word.uppercase()
                when {
                    Regex("\\d{1,2}[:.]\\d{2}").matches(word) -> time(word)
                    word.all { it.isDigit() } && word.length <= 6 -> cardinal(word.toInt())
                    upper in ABBREVIATIONS -> ABBREVIATIONS.getValue(upper)
                    word.length == 1 && word[0].isLetter() -> letter(word[0])
                    else -> word.lowercase()
                }
            }
            .replaceFirstChar { it.uppercaseChar() }
}
