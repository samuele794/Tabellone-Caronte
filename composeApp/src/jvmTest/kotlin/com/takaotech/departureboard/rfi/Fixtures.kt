package com.takaotech.departureboard.rfi

/**
 * Pagine RFI salvate in src/test/resources/rfi: Milano e Napoli il 13/10/2026 verso mezzanotte,
 * Firenze SMN, Milano Porta Garibaldi e Roma Termini il 14/10/2026 verso le 14:30 (note,
 * treni cancellati, marchi commerciali, linee suburbane, NO-STOP, fermate abbreviate).
 */
object Fixtures {
    fun load(name: String): String =
        checkNotNull(Fixtures::class.java.getResource("/rfi/$name")) { "fixture $name mancante" }.readText()

    val milanoPartenze get() = load("monitor_milano_partenze.html")
    val napoliArrivi get() = load("monitor_napoli_arrivi.html")
    val firenzePartenze get() = load("monitor_firenze_partenze.html")
    val garibaldiPartenze get() = load("monitor_garibaldi_partenze.html")
    val romaPartenze get() = load("monitor_roma_partenze.html")
    val stazioni get() = load("arrivipartenze.html")
}
