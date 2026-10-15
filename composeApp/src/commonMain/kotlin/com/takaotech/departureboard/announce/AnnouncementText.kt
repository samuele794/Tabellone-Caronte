package com.takaotech.departureboard.announce

import com.takaotech.departureboard.announce.SpokenItalian.carrier
import com.takaotech.departureboard.announce.SpokenItalian.category
import com.takaotech.departureboard.announce.SpokenItalian.minutes
import com.takaotech.departureboard.announce.SpokenItalian.platform
import com.takaotech.departureboard.announce.SpokenItalian.time
import com.takaotech.departureboard.announce.SpokenItalian.trainNumber
import com.takaotech.departureboard.rfi.RfiTrain

/**
 * Testo degli annunci secondo le formule del Manuale degli Annunci Sonori RFI. Il testo fisso è
 * quello del manuale; le pause brevi ("a capo") diventano virgole, quelle lunghe punti.
 *
 * Dalle note del monitor ("Informazioni") vengono "VIA …" (le relazioni di percorrenza, dopo la
 * destinazione), "NO-STOP" (nessuna fermata intermedia) e la composizione del treno, letta dopo
 * le fermate come nel MAS; le altre note seguono come frasi a sé. I campi che il monitor non
 * fornisce (treno straordinario, motivo del ritardo, treni alternativi) vengono omessi, come il
 * manuale consente per i testi aggiuntivi.
 *
 * [PlaceNames] scioglie le abbreviazioni delle località con l'elenco delle stazioni RFI.
 */
object AnnouncementText {

    /** Oltre questo ritardo il MAS aggiunge "in ritardo" alla partenza e le scuse al preavviso. */
    const val APOLOGY_DELAY = 15

    fun text(a: Announcement, places: PlaceNames = PlaceNames.None): String =
        Formulas(places).text(a)

    private class Formulas(private val places: PlaceNames) {

        fun text(a: Announcement): String = when (a) {
            is Announcement.Imminent -> if (a.departures) departing(a) else arriving(a)
            is Announcement.PlatformAssigned -> platform(a, previousPlatform = null)
            is Announcement.PlatformChange -> platform(a, a.previousPlatform)
            is Announcement.Delay -> delay(a)
            is Announcement.Cancelled -> cancelled(a)
        }

        /** P1, P2, P16. */
        private fun departing(a: Announcement.Imminent): String {
            val t = a.train
            val notes = notes(t)
            val late = (t.delayMinutes ?: 0) > APOLOGY_DELAY
            return sentences(
                if (t.isBus) {
                    "${subject(t)}, delle ore ${time(t.time)}, ${route(a)}, " +
                        "è previsto in partenza dal piazzale antistante la stazione"
                } else {
                    buildString {
                        append("${subject(t)}, delle ore ${time(t.time)}, ${route(a)}, è in partenza")
                        if (late) append(" in ritardo")
                        if (t.platform.isNotBlank()) append(", dal binario ${platform(t.platform)}")
                        a.previousPlatform?.let { append(", invece che dal binario ${platform(it)}") }
                    }
                },
                if (notes.noStop) NO_STOPS else stops(t),
                *notes.sentences.toTypedArray(),
            )
        }

        /** A1, A2: la composizione del treno viene dopo il richiamo alla linea gialla. */
        private fun arriving(a: Announcement.Imminent): String {
            val t = a.train
            return sentences(
                buildString {
                    append("${subject(t)}, delle ore ${time(t.time)}, ${route(a)}, è in arrivo")
                    if (t.platform.isNotBlank()) append(" al binario ${platform(t.platform)}")
                    a.previousPlatform?.let { append(", invece che al binario ${platform(it)}") }
                },
                if (t.isBus) null else "Attenzione! Allontanarsi dalla linea gialla",
                *notes(t).sentences.toTypedArray(),
            )
        }

        /** P8, A4: binario assegnato o, con [previousPlatform], variato. */
        private fun platform(a: Announcement, previousPlatform: String?): String {
            val t = a.train
            val verb = if (a.departures) "partirà dal binario" else "arriverà al binario"
            val previous = if (a.departures) "dal binario" else "al binario"
            return sentences(
                buildString {
                    append(
                        "Informiamo i viaggiatori che ${
                            subject(
                                t,
                                lowercase = true
                            )
                        }, delle ore ${time(t.time)}, "
                    )
                    append("${route(a)}, $verb ${platform(t.platform)}")
                    if (previousPlatform != null) append(
                        " invece che $previous ${
                            platform(
                                previousPlatform
                            )
                        }"
                    )
                },
            )
        }

        /** P5, A3. */
        private fun delay(a: Announcement.Delay): String {
            val t = a.train
            return sentences(
                "Annuncio ritardo!",
                buildString {
                    append("${subject(t)}, delle ore ${time(t.time)}, ${route(a)}, ")
                    if (a.departures) {
                        append("partirà")
                        if (t.platform.isNotBlank()) append(" dal binario ${platform(t.platform)}")
                    } else {
                        append("arriverà")
                    }
                    append(" con un ritardo previsto di ${minutes(a.minutes)} minuti")
                    if (a.previouslyAnnounced != null) append(", diversamente da quanto già annunciato")
                },
                if (a.minutes > APOLOGY_DELAY) "Ci scusiamo per il disagio" else null,
            )
        }

        /** P9, A5. */
        private fun cancelled(a: Announcement.Cancelled): String {
            val t = a.train
            return sentences(
                "Annuncio cancellazione treno!",
                if (a.departures) {
                    "${subject(t)}, previsto in partenza alle ore ${time(t.time)}, ${route(a)}, " +
                        "oggi non sarà effettuato"
                } else {
                    "${subject(t)}, previsto in arrivo da ${places.spoken(t.station)}, alle ore ${
                        time(
                            t.time
                        )
                    }, " +
                        "oggi non è stato effettuato"
                },
                "Ci scusiamo per il disagio",
            )
        }

        /**
         * "Il treno Alta Velocità novantanove quindici di Trenitalia" (CATEGORIA NUMERO di IMPRESA
         * FERROVIARIA); senza categoria "Il treno …"; per i bus "L'autobus sostitutivo …".
         */
        private fun subject(t: RfiTrain, lowercase: Boolean = false): String {
            val number = trainNumber(t.number)
            val category = category(t.category)
            val head = when {
                t.isBus -> "L'autobus sostitutivo $number"
                category.isEmpty() -> "Il treno $number"
                else -> "Il treno $category $number"
            }
            val withCarrier = if (t.carrier.isBlank()) head else "$head di ${carrier(t.carrier)}"
            return if (lowercase) withCarrier.replaceFirstChar { it.lowercaseChar() } else withCarrier
        }

        /** "per Napoli Centrale, via Aversa" in partenza; "proveniente da …" in arrivo. */
        private fun route(a: Announcement): String {
            val station = places.spoken(a.train.station)
            if (!a.departures) return "proveniente da $station"
            val via = notes(a.train).via
            return if (via.isEmpty()) "per $station" else "per $station, via ${list(via)}"
        }

        /**
         * "Ferma a Milano Lambrate, Pioltello Limito e Treviglio Ovest". La destinazione, ultima
         * dell'elenco RFI, è già nell'annuncio; se resta solo quella non ci sono fermate intermedie.
         * Senza informazioni non si dice nulla: non è detto che il treno non fermi.
         */
        private fun stops(t: RfiTrain): String? {
            val all = SpokenItalian.stops(t.info).ifEmpty { return null }
            val stops = if (isDestination(all.last(), t.station)) all.dropLast(1) else all
            return if (stops.isEmpty()) NO_STOPS else "Ferma a ${list(stops.map(places::spoken))}"
        }

        /** "GARBAGNATE MIL." in fondo alle fermate è la destinazione GARBAGNATE MILANESE. */
        private fun isDestination(stop: String, station: String) =
            places.spoken(stop).equals(places.spoken(station), ignoreCase = true) ||
                    PlaceNames(listOf(station)).resolve(stop) != null

        private class Notes(val via: List<String>, val noStop: Boolean, val sentences: List<String>)

        private fun notes(t: RfiTrain): Notes {
            val via = mutableListOf<String>()
            var noStop = false
            val sentences = mutableListOf<String>()
            var afterVia = false
            for (note in t.notes) {
                val upper = note.trim().uppercase()
                when {
                    upper.startsWith("VIA ") -> via += places.spoken(note.trim().drop(4))
                    // "VIA VILLAPIZZONE - MILANO LAMBRATE": il trattino separa anche le località.
                    afterVia && places.resolve(upper) != null -> via += places.spoken(note)
                    upper == "NO-STOP" || upper == "NO STOP" -> noStop = true
                    else -> sentences += composition(upper) ?: SpokenItalian.sentence(note)
                }
                afterVia = upper.startsWith("VIA ") || (afterVia && places.resolve(upper) != null)
            }
            return Notes(via, noStop, sentences)
        }

        /**
         * Formule dell'appendice "Composizione del treno" del MAS: "PRIMA CLASSE IN TESTA" →
         * "La prima classe è in testa al treno"; sullo stesso modello la posizione della carrozza 1.
         */
        private fun composition(note: String): String? {
            Regex("PRIMA CLASSE (?:È )?(IN TESTA|IN CODA|AL CENTRO)(?: (?:AL|DEL) TRENO)?").matchEntire(
                note
            )?.let {
                val where = it.groupValues[1].lowercase()
                return "La prima classe è $where ${if (where == "al centro") "del" else "al"} treno"
            }
            Regex("CARROZZA (\\d+) (?:È )?IN (TESTA|CODA) AL TRENO").matchEntire(note)?.let {
                val (n, where) = it.destructured
                return "La carrozza ${SpokenItalian.cardinal(n.toInt())} è in ${where.lowercase()} al treno"
            }
            return null
        }

        private fun list(items: List<String>) =
            if (items.size == 1) items.single() else "${
                items.dropLast(1).joinToString(", ")
            } e ${items.last()}"
    }

    private const val NO_STOPS = "Non sono previste fermate intermedie"

    /** Unisce le frasi con pause lunghe (punto), senza raddoppiare i punti esclamativi. */
    private fun sentences(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }
            .joinToString(" ") { if (it.last() in ".!?") it else "$it." }
}
