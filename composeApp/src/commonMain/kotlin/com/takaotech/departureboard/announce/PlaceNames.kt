package com.takaotech.departureboard.announce

/**
 * Scioglie le abbreviazioni delle fermate RFI ("CASTIGL.FIORENT.", "MI.P.GARIBALDI",
 * "LA SPEZIA C.") cercandole nell'elenco completo delle località (`ElencoLocalita`, che l'app
 * scarica già): ogni parola abbreviata dev'essere l'inizio della parola corrispondente di un
 * nome dell'elenco, nello stesso ordine. Se il nome non si trova, o i candidati sono più d'uno
 * a pari merito, si usano solo le regole di [SpokenItalian.place].
 */
class PlaceNames(names: Collection<String>) {

    private val stations: List<Pair<String, List<Token>>> =
        names.distinct().map { it to tokens(it) }.filter { it.second.isNotEmpty() }
    private val exact = stations.mapTo(HashSet()) { it.first.uppercase() }
    private val cache = HashMap<String, String?>()

    /** Nome completo dell'elenco per [name], o null se non c'è un candidato unico. */
    fun resolve(name: String): String? {
        val key = SpokenItalian.normalizeApostrophes(name.trim()).uppercase()
        if (key.isEmpty() || stations.isEmpty()) return null
        if (key in exact) return key
        return cache.getOrPut(key) { bestMatch(key) }
    }

    /** Nome pronto per la voce: risolto quando possibile, poi letto con [SpokenItalian.place]. */
    fun spoken(name: String): String = SpokenItalian.place(resolve(name) ?: name)

    private fun bestMatch(key: String): String? {
        val abbreviated = tokens(key)
        if (abbreviated.isEmpty()) return null
        val scored =
            stations.mapNotNull { (name, words) -> score(abbreviated, words)?.let { name to it } }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /**
     * Punteggio se [abbreviated] corrisponde a [words], null se no: conta le parole uguali per
     * intero e, a parità, vince il nome con meno parole in più ("BORGO S.L." è BORGO S.LORENZO,
     * non BORGO S.LORENZO RIMORELLI).
     * Le parole brevi del nome completo (DI, DEL, IN, SUL…) si possono saltare. Le parole in più
     * alla fine sono ammesse se stanno dopo un trattino ("SESSA A." → SESSA AURUNCA-ROCCAMONFINA)
     * o se l'abbreviazione finisce col punto ("CORBETTA S.STEF." → CORBETTA-S.STEFANO TICINO);
     * un nome intero non si allunga: "BARI" non è BARI CENTRALE.
     */
    private fun score(abbreviated: List<Token>, words: List<Token>): Int? {
        var i = 0
        var exactWords = 0
        for ((k, t) in abbreviated.withIndex()) {
            while (i < words.size && !t.matches(words[i], first = k == 0)) {
                // "S" (santo) non si salta: "MARINO" non è S.MARINO.
                if (words[i].text.length > 3 || words[i].text == "S") return null
                i++
            }
            if (i == words.size) return null
            if (t.text == words[i].text) exactWords++
            i++
        }
        val lastSegment = words[i - 1].segment
        val openEnded = abbreviated.last().dotted
        val extra = words.drop(i)
        if (extra.any { it.text.length > 3 && it.segment == lastSegment && !openEnded }) return null
        // Un nome scritto per intero resta com'è: "COLLEFERRO" non diventa COLLEFERRO-SEGNI-PALIANO.
        if (exactWords == abbreviated.size && extra.isNotEmpty()) return null
        return exactWords * 100 - extra.size
    }

    /** Una parola: [dotted] se seguita dal punto, [segment] = parte del nome tra i trattini. */
    private data class Token(val text: String, val dotted: Boolean, val segment: Int) {
        /**
         * Parola uguale, o inizio di parola ("CASTIGL" → CASTIGLION). In testa al nome "S" vale
         * solo "S" (santo), altrimenti "S. MARCO" diventerebbe SASSO MARCONI.
         */
        fun matches(word: Token, first: Boolean) = when {
            text == word.text -> true
            first && (text == "S" || word.text == "S") -> false
            else -> word.text.startsWith(text)
        }
    }

    companion object {
        /** Senza elenco: solo le regole fisse di [SpokenItalian.place]. */
        val None = PlaceNames(emptyList())

        /** "MI.P.GARIBALDI" → MI. P. GARIBALDI; "C.LE" vale CENTRALE da entrambe le parti. */
        private fun tokens(name: String): List<Token> =
            Regex("\\bC\\.LE\\b").replace(
                SpokenItalian.normalizeApostrophes(name).uppercase(),
                "CENTRALE"
            )
                .split('-')
                .flatMapIndexed { segment, part ->
                    Regex("(\\p{L}+|\\d+)(\\.)?").findAll(part)
                        .map { Token(it.groupValues[1], it.groupValues[2].isNotEmpty(), segment) }
                }
    }
}
