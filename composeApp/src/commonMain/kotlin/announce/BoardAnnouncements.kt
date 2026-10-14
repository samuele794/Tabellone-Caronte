package announce

import debuglog.DebugLog
import rfi.BoardMode
import rfi.RfiBoard
import rfi.RfiMapper
import rfi.RfiTrain
import kotlin.math.abs

/**
 * Decide cosa annunciare confrontando il tabellone appena scaricato con il precedente.
 *
 * Il delta considera solo i treni presenti in entrambe le versioni: quelli appena comparsi o
 * spariti dal tabellone non si annunciano. Un treno che compare entra nella base così com'è
 * (ritardo e soppressione già visibili contano come annunciati) e dal giro dopo partecipa al delta.
 *
 * Il primo tabellone di una stazione/modalità (all'avvio o dopo un cambio) fa solo da base.
 * Un download fallito non va passato qui, così la base resta l'ultimo tabellone valido.
 */
class BoardAnnouncements {

    private var key: Pair<String, BoardMode>? = null
    private var previous: Map<String, RfiTrain> = emptyMap()

    /** Ultimo ritardo annunciato (o già visibile) per treno, per non ripeterlo a ogni aggiornamento. */
    private val announcedDelay = HashMap<String, Int>()
    private val announcedCancelled = HashSet<String>()

    fun update(stationId: String, mode: BoardMode, board: RfiBoard): List<Announcement> {
        val current = board.trains.associateBy { it.number }
        val label = "${board.stationName.ifBlank { stationId }} · ${mode.label}"
        if (key != stationId to mode) {
            key = stationId to mode
            previous = current
            announcedDelay.clear()
            announcedCancelled.clear()
            board.trains.forEach(::remember)
            DebugLog.d(TAG) { "Base $label: treni ${board.trains.size}, nessun annuncio" }
            return emptyList()
        }

        val added = board.trains.filter { it.number !in previous }
        val removed = previous.values.filter { it.number !in current }
        added.forEach(::remember)
        removed.forEach { announcedDelay.remove(it.number); announcedCancelled.remove(it.number) }

        val log = StringBuilder()
        val announcements = board.trains.mapNotNull { t ->
            val before = previous[t.number] ?: return@mapNotNull null
            val changes = changes(before, t)
            val announcement = announcement(t, before, mode)
            if (changes.isNotEmpty() || announcement != null) {
                log.append(
                    "\n  ${t.describe()}: ${
                        changes.joinToString(", ").ifEmpty { "nessuna variazione" }
                    }"
                )
                log.append(announcement?.let { "  ⇒ ${it.code}" } ?: "  ⇒ nessun annuncio")
            }
            announcement
        }
        previous = current
        DebugLog.d(TAG) {
            val common = board.trains.size - added.size
            "Delta $label: treni confrontati $common, annunci ${announcements.size} " +
                    "(esclusi: comparsi ${added.size}, spariti ${removed.size})" +
                    log.ifEmpty { "\n  nessuna variazione" }
        }
        return announcements
    }

    /**
     * Dimentica la base: il prossimo [update] fa di nuovo solo da base. Serve quando il tabellone
     * è rimasto fermo (app in background) e le variazioni accumulate non vanno annunciate in blocco.
     */
    fun reset() {
        key = null
    }

    /** Un treno entra nella base: quello che mostra già non va annunciato. */
    private fun remember(t: RfiTrain) {
        t.delayMinutes?.takeIf { it >= DELAY_THRESHOLD }?.let { announcedDelay[t.number] = it }
        if (t.cancelled) announcedCancelled += t.number
    }

    private fun announcement(t: RfiTrain, before: RfiTrain, mode: BoardMode): Announcement? {
        if (t.cancelled) {
            return if (announcedCancelled.add(t.number)) Announcement.Cancelled(t, mode) else null
        }
        val delay = t.delayMinutes ?: 0
        val platformBefore = before.platform.trim().takeIf {
            it.isNotEmpty() && t.platform.isNotBlank() && it != t.platform.trim()
        }
        if (t.flashing) {
            // Il ritardo, se c'è, lo dice già l'annuncio di partenza ("in ritardo").
            if (delay >= DELAY_THRESHOLD) announcedDelay[t.number] = delay
            return if (!before.flashing) Announcement.Imminent(t, mode, platformBefore) else null
        }
        if (platformBefore != null) return Announcement.PlatformChange(t, mode, platformBefore)
        if (before.platform.isBlank() && t.platform.isNotBlank()) return Announcement.PlatformAssigned(
            t,
            mode
        )
        if (delay >= DELAY_THRESHOLD) {
            val last = announcedDelay[t.number]
            if (last == null || abs(delay - last) >= DELAY_STEP) {
                announcedDelay[t.number] = delay
                return Announcement.Delay(t, mode, delay, last)
            }
        }
        return null
    }

    /** Campi del treno cambiati tra le due versioni, per il log. */
    private fun changes(before: RfiTrain, t: RfiTrain) = buildList {
        if (before.platform != t.platform) add("binario '${before.platform}' → '${t.platform}'")
        if (before.delayMinutes != t.delayMinutes) add("ritardo ${before.delayMinutes ?: 0} → ${t.delayMinutes ?: 0}")
        if (before.flashing != t.flashing) add("spie ${onOff(before.flashing)} → ${onOff(t.flashing)}")
        if (before.cancelled != t.cancelled) add("soppresso ${yesNo(before.cancelled)} → ${yesNo(t.cancelled)}")
        if (before.time != t.time) add("orario ${before.time} → ${t.time}")
        if (before.station != t.station) add("località ${before.station} → ${t.station}")
    }

    private fun onOff(b: Boolean) = if (b) "accese" else "spente"
    private fun yesNo(b: Boolean) = if (b) "sì" else "no"

    companion object {
        /** Il MAS annuncia il ritardo da 10 minuti in su. */
        const val DELAY_THRESHOLD = 10

        /** Variazione minima, rispetto all'ultimo annuncio, per riannunciare il ritardo. */
        const val DELAY_STEP = 5

        const val TAG = "Tabellone"
    }
}

internal val BoardMode.label get() = if (this == BoardMode.Departures) "partenze" else "arrivi"

/** "AV 9915 BARI C.LE 07:40", per i log. */
internal fun RfiTrain.describe() =
    "${RfiMapper.categoryCode(category).ifBlank { "?" }} $number $station $time"
