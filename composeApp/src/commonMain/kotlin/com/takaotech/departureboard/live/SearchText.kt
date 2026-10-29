package com.takaotech.departureboard.live

/**
 * Testo cercato tra i nomi delle stazioni, uguale per la ricerca delle impostazioni e per quella
 * della mappa: non contano maiuscole, accenti e spazi ai bordi ("cantù" trova "CANTU"), e
 * l'apostrofo tipografico delle tastiere vale come quello dritto dei nomi.
 */
internal class SearchText(text: String) {
    private val key = normalized(text.trim())

    /** Non c'è niente da cercare: si mostra tutto, o niente se servono suggerimenti. */
    val isEmpty: Boolean get() = key.isEmpty()

    /** [name] contiene il testo cercato. */
    fun matches(name: String): Boolean = normalized(name).contains(key)

    /** [name] inizia con il testo cercato. */
    fun isPrefixOf(name: String): Boolean = normalized(name).startsWith(key)

    private companion object {
        /** Lettere accentate e apostrofi tipografici con il loro equivalente semplice. */
        private val PLAIN: Map<Char, Char> = buildMap {
            fun add(plain: Char, variants: String) = variants.forEach { put(it, plain) }
            add('A', "ÀÁÂÃÄÅ")
            add('E', "ÈÉÊË")
            add('I', "ÌÍÎÏ")
            add('O', "ÒÓÔÕÖ")
            add('U', "ÙÚÛÜ")
            add('C', "Ç")
            add('N', "Ñ")
            add('\'', "’‘`´")
        }

        fun normalized(text: String): String =
            buildString(text.length) { text.uppercase().forEach { append(PLAIN[it] ?: it) } }
    }
}
