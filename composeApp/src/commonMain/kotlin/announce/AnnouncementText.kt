package announce

import announce.SpokenItalian.carrier
import announce.SpokenItalian.category
import announce.SpokenItalian.minutes
import announce.SpokenItalian.place
import announce.SpokenItalian.platform
import announce.SpokenItalian.time
import announce.SpokenItalian.trainNumber
import rfi.RfiTrain

/**
 * Testo degli annunci secondo le formule del Manuale degli Annunci Sonori RFI. Il testo fisso è
 * quello del manuale; le pause brevi ("a capo") diventano virgole, quelle lunghe punti.
 *
 * I campi che il monitor RFI non fornisce (treno straordinario, motivo del ritardo, treni
 * alternativi, composizione) vengono omessi, come il manuale consente per i testi aggiuntivi.
 */
object AnnouncementText {

    /** Oltre questo ritardo il MAS aggiunge "in ritardo" alla partenza e le scuse al preavviso. */
    const val APOLOGY_DELAY = 15

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
        val late = (t.delayMinutes ?: 0) > APOLOGY_DELAY
        return sentences(
            if (t.isBus) {
                "${subject(t)}, delle ore ${time(t.time)}, per ${place(t.station)}, " +
                        "è previsto in partenza dal piazzale antistante la stazione"
            } else {
                buildString {
                    append("${subject(t)}, delle ore ${time(t.time)}, per ${place(t.station)}, è in partenza")
                    if (late) append(" in ritardo")
                    if (t.platform.isNotBlank()) append(", dal binario ${platform(t.platform)}")
                    a.previousPlatform?.let { append(", invece che dal binario ${platform(it)}") }
                }
            },
            stops(t),
        )
    }

    /** A1, A2. */
    private fun arriving(a: Announcement.Imminent): String {
        val t = a.train
        return sentences(
            buildString {
                append("${subject(t)}, delle ore ${time(t.time)}, proveniente da ${place(t.station)}, è in arrivo")
                if (t.platform.isNotBlank()) append(" al binario ${platform(t.platform)}")
                a.previousPlatform?.let { append(", invece che al binario ${platform(it)}") }
            },
            if (t.isBus) null else "Attenzione! Allontanarsi dalla linea gialla",
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
                "${subject(t)}, previsto in partenza alle ore ${time(t.time)}, per ${place(t.station)}, " +
                        "oggi non sarà effettuato"
            } else {
                "${subject(t)}, previsto in arrivo da ${place(t.station)}, alle ore ${time(t.time)}, " +
                        "oggi non è stato effettuato"
            },
            "Ci scusiamo per il disagio",
        )
    }

    /** "Il treno Alta Velocità novantanove quindici di Trenitalia"; per i bus "L'autobus sostitutivo …". */
    private fun subject(t: RfiTrain, lowercase: Boolean = false): String {
        val number = trainNumber(t.number)
        val head =
            if (t.isBus) "L'autobus sostitutivo $number" else "Il treno ${category(t.category)} $number"
        val withCarrier = if (t.carrier.isBlank()) head else "$head di ${carrier(t.carrier)}"
        return if (lowercase) withCarrier.replaceFirstChar { it.lowercaseChar() } else withCarrier
    }

    private fun route(a: Announcement) =
        if (a.departures) "per ${place(a.train.station)}" else "proveniente da ${place(a.train.station)}"

    /**
     * "Ferma a Milano Lambrate, Pioltello Limito e Treviglio Ovest". La destinazione, ultima
     * dell'elenco RFI, è già nell'annuncio; se resta solo quella non ci sono fermate intermedie.
     * Senza informazioni non si dice nulla: non è detto che il treno non fermi.
     */
    private fun stops(t: RfiTrain): String? {
        val all = SpokenItalian.stops(t.info).ifEmpty { return null }
        val stops =
            if (all.last().equals(place(t.station), ignoreCase = true)) all.dropLast(1) else all
        return when (stops.size) {
            0 -> "Non sono previste fermate intermedie"
            1 -> "Ferma a ${stops.single()}"
            else -> "Ferma a ${stops.dropLast(1).joinToString(", ")} e ${stops.last()}"
        }
    }

    /** Unisce le frasi con pause lunghe (punto), senza raddoppiare i punti esclamativi. */
    private fun sentences(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }
            .joinToString(" ") { if (it.last() in ".!?") it else "$it." }
}
