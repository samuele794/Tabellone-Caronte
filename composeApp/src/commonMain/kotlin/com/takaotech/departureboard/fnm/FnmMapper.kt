package com.takaotech.departureboard.fnm

import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import kotlinx.datetime.LocalTime

/**
 * Adatta un treno FNM (riga dell'elenco + dettaglio) a una riga nel formato del monitor RFI,
 * così tabellone e annunci non cambiano.
 */
internal object FnmMapper {

    /** Le spie lampeggiano quando mancano al più tanti minuti all'arrivo o alla partenza. */
    const val FLASH_MINUTES = 3

    const val CARRIER = "TRENORD"

    /**
     * null quando il treno non va più mostrato: in partenza è già partito o termina qui, in
     * arrivo è già arrivato o parte da qui. Senza dettaglio la riga usa i soli dati dell'elenco.
     */
    fun train(
        row: FnmListTrain,
        detail: FnmTrainDetail?,
        station: Station,
        mode: BoardMode,
        now: LocalTime,
    ): RfiTrain? {
        val departures = mode == BoardMode.Departures
        val index = detail?.stops?.indexOfFirst { it.isAt(station) } ?: -1
        if (detail == null || index < 0) return listOnly(row, detail, departures)

        val stop = detail.stops[index]
        val stopTime = (if (departures) stop.depTime else stop.arrTime) ?: return null
        if (if (departures) stop.depActual != null else stop.arrActual != null) return null
        // L'elenco ha sempre l'orario programmato; il dettaglio HTML, per le fermate future, no.
        val scheduled = row.time.ifBlank { hhmm(stopTime) }

        val expected = if (departures) stop.depEstimated else stop.arrEstimated
        val delay = expected?.let { minutesBetween(scheduled, it) } ?: detail.delayMinutes
        val previous = detail.stops.getOrNull(index - 1)
        val inStation = departures && stop.arrActual != null
        val approaching = previous?.depActual != null &&
                minutesUntil(now, stop.arrEstimated ?: stop.arrTime ?: scheduled) <= FLASH_MINUTES
        val leavingFromHere = departures && previous == null &&
                minutesUntil(now, stop.depEstimated ?: scheduled) <= FLASH_MINUTES

        return RfiTrain(
            carrier = detail.carrier ?: CARRIER,
            carrierLogoBase64 = TrenordLogo.BASE64,
            category = detail.category ?: row.line,
            number = row.number,
            station = if (departures) detail.destination else detail.origin,
            time = scheduled,
            delayMinutes = delay?.takeIf { it > 0 },
            platform = stop.platform,
            flashing = inStation || approaching || leavingFromHere,
            info = if (departures) stops(detail.stops.drop(index + 1)) else "",
            notes = detail.notes,
            cancelled = detail.cancelled || stop.cancelled,
        )
    }

    /** Riga con i soli dati dell'elenco: in arrivo la provenienza si sa solo dal dettaglio. */
    private fun listOnly(row: FnmListTrain, detail: FnmTrainDetail?, departures: Boolean) =
        RfiTrain(
            carrier = detail?.carrier ?: CARRIER,
            carrierLogoBase64 = TrenordLogo.BASE64,
            category = detail?.category ?: row.line,
            number = row.number,
            station = if (departures) detail?.destination
                ?: row.destination else detail?.origin.orEmpty(),
            time = row.time,
            delayMinutes = detail?.delayMinutes?.takeIf { it > 0 },
            platform = "",
            flashing = false,
            info = "",
            notes = detail?.notes.orEmpty(),
            cancelled = detail?.cancelled == true,
        )

    /** Nello stesso formato del monitor RFI: "FERMA A: SARONNO (17:29) - MILANO CADORNA (17:52)". */
    private fun stops(next: List<FnmStop>): String {
        val stops = next.filter { !it.cancelled && it.name.isNotBlank() }
        if (stops.isEmpty()) return ""
        return "FERMA A: " + stops.joinToString(" - ") { s ->
            val time = s.arrTime ?: s.depTime
            if (time == null) s.name else "${s.name} (${hhmm(time)})"
        }
    }

    private fun FnmStop.isAt(station: Station) =
        if (mir != null) mir == station.id else name.equals(station.name.trim(), ignoreCase = true)

    /** "17:24:00" → "17:24". */
    fun hhmm(time: String): String = seconds(time)?.let {
        val minutes = it / 60
        "${(minutes / 60).toString().padStart(2, '0')}:${
            (minutes % 60).toString().padStart(2, '0')
        }"
    } ?: time

    /** Minuti interi da [from] a [to], sullo stesso giro di 24 ore (±12 ore). */
    fun minutesBetween(from: String, to: String): Int? {
        val a = seconds(from) ?: return null
        val b = seconds(to) ?: return null
        return wrap(b - a) / 60
    }

    /** Minuti (anche negativi) che mancano a [time]. */
    fun minutesUntil(now: LocalTime, time: String): Int {
        val t = seconds(time) ?: return Int.MAX_VALUE
        return wrap(t - now.toSecondOfDay()) / 60
    }

    /** Secondi di differenza riportati in [-12 h, +12 h). */
    fun wrap(seconds: Int): Int = ((seconds + HALF_DAY) % DAY + DAY) % DAY - HALF_DAY

    /** "HH:MM" o "HH:MM:SS" → secondi dalla mezzanotte. */
    fun seconds(time: String): Int? {
        val parts = time.trim().split(':')
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    private const val DAY = 24 * 3600
    private const val HALF_DAY = 12 * 3600
}
