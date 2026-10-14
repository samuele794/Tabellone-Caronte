package rfi

/** Pagine RFI salvate in src/test/resources/rfi (13/10/2026, verso mezzanotte). */
object Fixtures {
    fun load(name: String): String =
        checkNotNull(Fixtures::class.java.getResource("/rfi/$name")) { "fixture $name mancante" }.readText()

    val milanoPartenze get() = load("monitor_milano_partenze.html")
    val napoliArrivi get() = load("monitor_napoli_arrivi.html")
    val stazioni get() = load("arrivipartenze.html")
}
