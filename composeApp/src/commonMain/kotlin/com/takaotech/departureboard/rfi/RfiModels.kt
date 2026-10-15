package com.takaotech.departureboard.rfi

import kotlinx.serialization.Serializable

/** Una località dell'elenco RFI (`<select id="ElencoLocalita">`). */
@Serializable
data class Station(val id: String, val name: String)

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
    /** Blocco "Informazioni": "CARROZZA 1 IN TESTA AL TRENO", "GATE B", "VIA AVERSA", "NO-STOP"… */
    val notes: List<String> = emptyList(),
    /** Treno cancellato/soppresso, se il monitor lo segnala nella cella del ritardo o nelle info. */
    val cancelled: Boolean = false,
    /** Testo della cella del ritardo quando non è un numero ("RITARDO", "Cancellato"); vuoto altrimenti. */
    val delayText: String = "",
) {
    override fun toString() =
        "RfiTrain($carrier $category $number → $station $time +$delayMinutes bin=$platform flashing=$flashing cancelled=$cancelled)"
}

/** Il contenuto utile di una pagina del monitor. */
data class RfiBoard(
    val stationName: String,
    val mode: BoardMode,
    val trains: List<RfiTrain>,
    /** Avvisi della barra gialla in basso. */
    val notices: List<String>,
)
