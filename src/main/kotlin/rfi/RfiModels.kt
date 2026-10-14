package rfi

/** Una località dell'elenco RFI (`<select id="ElencoLocalita">`). */
data class Station(val id: Int, val name: String)

/** Quale tabella del monitor leggere: corrisponde al parametro `arrivals` dell'URL. */
enum class BoardMode(val arrivalsParam: String) {
    Departures("False"),
    Arrivals("True"),
}

/** Una riga del monitor RFI così com'è sulla pagina, prima di adattarla al tabellone. */
class RfiTrain(
    /** Impresa ferroviaria (alt del logo): "TRENITALIA", "ITALO", "TRENORD"… */
    val carrier: String,
    /** GIF del logo, ancora in base64 (la stringa fa anche da chiave di cache). */
    val carrierLogoBase64: String?,
    /** Categoria per esteso, senza il prefisso "Categoria ": "ALTA VELOCITA'", "REG", "BUS"… */
    val category: String,
    val number: String,
    /** Destinazione in partenza, provenienza in arrivo. */
    val station: String,
    /** "HH:MM" */
    val time: String,
    /** Minuti; null = nessun ritardo indicato. */
    val delayMinutes: Int?,
    val platform: String,
    /** Spia "in partenza" / "in arrivo". */
    val flashing: Boolean,
    /** Fermate successive ("FERMA A: …"), vuoto se non c'è. */
    val info: String,
) {
    override fun toString() =
        "RfiTrain($carrier $category $number → $station $time +$delayMinutes bin=$platform flashing=$flashing)"
}

/** Il contenuto utile di una pagina del monitor. */
data class RfiBoard(
    val stationName: String,
    val mode: BoardMode,
    val trains: List<RfiTrain>,
    /** Avvisi della barra gialla in basso. */
    val notices: List<String>,
)
