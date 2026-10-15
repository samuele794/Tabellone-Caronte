package com.takaotech.departureboard

/**
 * Tamburo di una palette Solari: i caratteri si susseguono sempre nello stesso ordine e la
 * palette gira solo in avanti, quindi per passare da "A" a "C" si vede anche la "B".
 */
internal object FlapDrum {
    /** Ordine delle palette sul tamburo; lo spazio (palette vuota) è la prima. */
    const val SEQUENCE = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,:;'\"-_/()&+!?°<>=*"

    /**
     * Tamburo delle sole cifre, come le unità numeriche di orari e minuti: da "9" a "0" basta un
     * giro invece di fare il giro di tutte le lettere.
     */
    const val DIGITS = "0123456789"

    private fun drum(from: Char, to: Char) =
        if (from in '0'..'9' && to in '0'..'9') DIGITS else SEQUENCE

    /**
     * Giri necessari per passare da [from] a [to]: 0 se sono uguali, uno solo se uno dei due non
     * è sul tamburo (la palette si sostituisce di colpo).
     */
    fun steps(from: Char, to: Char): Int {
        if (from == to) return 0
        val drum = drum(from, to)
        val a = drum.indexOf(from)
        val b = drum.indexOf(to)
        if (a < 0 || b < 0) return 1
        return (b - a + drum.length) % drum.length
    }

    /** Carattere mostrato dopo [k] giri partendo da [from] verso [to] (l'ultimo giro dà [to]). */
    fun charAfter(from: Char, to: Char, k: Int): Char {
        val total = steps(from, to)
        if (k <= 0) return from
        if (k >= total) return to
        val drum = drum(from, to)
        return drum[(drum.indexOf(from) + k) % drum.length]
    }
}

/**
 * Divide [text] in pagine di al più [chars] caratteri, andando a capo tra una parola e l'altra:
 * le palette non scorrono, quindi un testo lungo si mostra a pagine. Le parole più lunghe di una
 * pagina si spezzano. Il testo è già normalizzato come sui tabelloni ("È" → "E'").
 */
internal fun flapPages(text: String, chars: Int): List<String> {
    val normalized = DotMatrixFont.normalize(text).trim()
    if (chars <= 0) return listOf("")
    if (normalized.length <= chars) return listOf(normalized)
    val pages = mutableListOf<String>()
    val line = StringBuilder()
    for (word in normalized.split(' ').filter { it.isNotEmpty() }) {
        var rest = word
        while (rest.isNotEmpty()) {
            val sep = if (line.isEmpty()) 0 else 1
            when {
                line.length + sep + rest.length <= chars -> {
                    if (sep == 1) line.append(' ')
                    line.append(rest)
                    rest = ""
                }

                line.isNotEmpty() -> {
                    pages += line.toString()
                    line.clear()
                }

                else -> {
                    pages += rest.take(chars)
                    rest = rest.drop(chars)
                }
            }
        }
    }
    if (line.isNotEmpty()) pages += line.toString()
    return pages.ifEmpty { listOf("") }
}
