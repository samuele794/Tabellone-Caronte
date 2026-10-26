package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Un treno seguito sullo smartwatch: [trainNumber] in partenza da [station]. Vale ogni giorno,
 * finché non lo si toglie.
 */
@Serializable
data class WatchSelection(
    val source: StationSource,
    val station: Station,
    val trainNumber: String,
    /** "R 91831 → VIGNOLA": si mostra anche quando il treno non è (ancora) in tabellone. */
    val label: String,
    /**
     * Orario programmato quando è stato scelto, "HH:MM": il watch sa quando controllarlo anche
     * prima di averlo visto in tabellone. null se il treno è stato scelto solo per numero e non è
     * ancora comparso, e nelle selezioni salvate prima che ci fosse.
     */
    val time: String? = null,
) {
    /** Chiave del treno, uguale per [sameTrain]. */
    val key: String get() = "${source.name}/${station.id}/$trainNumber"

    /** Etichetta e orario sono ancora da prendere dal tabellone, la prima volta che il treno c'è. */
    val awaitingDetails: Boolean get() = time == null

    /** Stesso treno da seguire (fornitore, stazione e numero), anche se etichetta e orario no. */
    fun sameTrain(other: WatchSelection?): Boolean =
        other != null && source == other.source && station == other.station && trainNumber == other.trainNumber

    /** Con etichetta e orario di [train], visto in tabellone, se ancora mancavano. */
    fun completedWith(train: WatchedTrain): WatchSelection =
        if (awaitingDetails) of(source, station, train) else this

    /**
     * Questa selezione, oppure [known] se è lo stesso treno già trovato in tabellone mentre questa
     * aspetta ancora: etichetta e orario veri non tornano indietro.
     */
    fun orDetailsOf(known: WatchSelection?): WatchSelection =
        if (awaitingDetails && known != null && !known.awaitingDetails && sameTrain(known)) known else this

    companion object {
        fun of(source: StationSource, station: Station, train: WatchedTrain) = WatchSelection(
            source = source,
            station = station,
            trainNumber = train.number,
            label = "${train.category} ${train.number} → ${train.destination}",
            time = train.time,
        )

        /**
         * Il treno [number] da [station], scelto senza vederlo in tabellone: etichetta e orario
         * arrivano con [completedWith] la prima volta che compare.
         */
        fun byNumber(source: StationSource, station: Station, number: String) = WatchSelection(
            source = source,
            station = station,
            trainNumber = number,
            label = "Treno $number",
        )
    }
}

/** Il treno seguito così come lo mostra il monitor della stazione. */
@Serializable
data class WatchedTrain(
    /** Impresa ferroviaria: "TRENITALIA TPER", "ITALO"… */
    val carrier: String,
    /** GIF del logo in base64, come la dà il monitor RFI; null se non c'è. */
    val carrierLogoBase64: String? = null,
    /** Categoria abbreviata ("R", "RV", "AV"…), come sul tabellone. */
    val category: String,
    val number: String,
    val destination: String,
    /** Orario programmato, "HH:MM". */
    val time: String,
    /** Minuti; null = nessun ritardo indicato. */
    val delayMinutes: Int? = null,
    /** Testo della cella del ritardo quando non è un numero ("RITARDO"); vuoto altrimenti. */
    val delayText: String = "",
    val platform: String = "",
    /** Fermate e note, come nella colonna "informazioni". */
    val info: String = "",
    val cancelled: Boolean = false,
    /** Spia "in partenza". */
    val flashing: Boolean = false,
    /** Stazione del monitor da cui viene la riga. */
    val stationName: String = "",
) {
    companion object {
        fun of(train: RfiTrain, stationName: String) = WatchedTrain(
            carrier = train.carrier,
            carrierLogoBase64 = train.carrierLogoBase64,
            category = RfiMapper.categoryCode(train.category),
            number = train.number.trim(),
            destination = train.station,
            time = train.time,
            delayMinutes = train.delayMinutes,
            delayText = train.delayText,
            platform = train.platform,
            info = RfiMapper.info(train),
            cancelled = train.cancelled,
            flashing = train.flashing,
            stationName = stationName,
        )
    }
}

/** Esito dell'ultimo controllo del treno. */
enum class WatchStatus {
    /** Il treno è sul monitor della stazione: [WatchSnapshot.train] è aggiornato. */
    OnBoard,

    /** Il monitor non lo mostra (oggi non è ancora in tabellone, o è già partito). */
    NotOnBoard,

    /** Il monitor non si è potuto leggere. */
    Error,
}

/** Perché il watch non è riuscito ad aggiornare da sé. */
enum class WatchOffline {
    /** Né telefono raggiungibile né Wi-Fi/LTE. */
    NoNetwork,

    /** C'è una rete, ma internet non risponde (portale di accesso, rete senza uscita…). */
    NoInternet,
}

/**
 * Quello che il watch sa dei treni seguiti: tutti, in ordine di osservazione ([plan]), e quello
 * mostrato adesso ([selection], vedi [WatchPlan]) con l'esito del suo ultimo controllo. [train]
 * resta quello dell'ultima volta che il treno mostrato era in tabellone, anche quando [status] dice
 * che ora non c'è.
 */
@Serializable
data class WatchSnapshot(
    val selection: WatchSelection? = null,
    val train: WatchedTrain? = null,
    /** null finché non c'è stato nessun controllo per [selection]. */
    val status: WatchStatus? = null,
    /** Fine dell'ultimo controllo, in millisecondi dall'epoch; 0 se non ce n'è stato. */
    val updatedAt: Long = 0,
    /** Messaggio dell'errore, con [WatchStatus.Error]. */
    val error: String? = null,
    /** Perché l'ultimo tentativo non è partito; null se è partito (o non ce n'è stato). */
    val offline: WatchOffline? = null,
    /** Ultimo tentativo non partito ([offline]), in millisecondi dall'epoch; 0 se non ce n'è. */
    val attemptedAt: Long = 0,
    /** Tutti i treni seguiti, in ordine di osservazione; vuoto negli snapshot di prima. */
    val plan: List<WatchSelection> = emptyList(),
    /** Treni ([WatchSelection.key]) visti sparire dal tabellone nella giornata [day]. */
    val departed: List<String> = emptyList(),
    /** Giornata dei treni di [departed] ([WatchPlan.serviceDay]), "2026-10-26". */
    val day: String? = null,
) {
    /** Ultimo tentativo, riuscito o no: da qui il watch conta l'attesa per il prossimo. */
    val lastAttemptAt: Long get() = maxOf(updatedAt, attemptedAt)

    /** I treni seguiti; negli snapshot di prima dei treni multipli, solo [selection]. */
    val followed: List<WatchSelection> get() = plan.ifEmpty { listOfNotNull(selection) }

    /**
     * Con i treni [trains] alle [now]: ne tiene etichetta e orario già trovati, li mette in ordine,
     * dimentica i treni partiti ieri e passa al primo treno non ancora partito. Se è lo stesso di
     * prima se ne tiene l'ultimo controllo; se è un altro si riparte da zero.
     */
    fun planned(trains: List<WatchSelection>, now: Instant): WatchSnapshot {
        val today = WatchPlan.serviceDay(now)
        val plan = WatchPlan.withDetails(trains, followed)
        val keys = plan.mapTo(HashSet()) { it.key }
        val base = copy(
            plan = plan,
            departed = if (day == today.toString()) departed.filter { it in keys } else emptyList(),
            day = today.toString(),
        )
        // Partiti tutti: si mostra il primo, che è il prossimo di domani.
        val next = plan.firstOrNull { !base.gone(it, now) } ?: plan.firstOrNull()
        return when {
            next == null -> WatchSnapshot(plan = plan, day = base.day)
            next.sameTrain(selection) -> base.copy(selection = next)
            else -> WatchSnapshot(
                selection = next,
                plan = plan,
                departed = base.departed,
                day = base.day
            )
        }
    }

    /**
     * Esito del controllo del treno mostrato: [found] è com'è adesso in tabellone, null se non c'è.
     * Se c'è per la prima volta prende etichetta e orario (e magari un altro posto nell'ordine); se
     * era in tabellone e ora non c'è più è partito, e si passa al successivo.
     */
    fun observed(found: WatchedTrain?, now: Instant): WatchSnapshot {
        val shown = selection ?: return this
        val nowMillis = now.toEpochMilliseconds()
        val seenToday = status == WatchStatus.OnBoard && updatedAt > 0 &&
                WatchPlan.serviceDay(Instant.fromEpochMilliseconds(updatedAt)) == WatchPlan.serviceDay(
            now
        )
        val current = found?.let(shown::completedWith) ?: shown
        val checked = copy(
            selection = current,
            train = found ?: train,
            status = if (found != null) WatchStatus.OnBoard else WatchStatus.NotOnBoard,
            updatedAt = nowMillis,
            error = null,
            offline = null,
            attemptedAt = 0,
        )
        val leftBoard = found == null && seenToday && shown.key !in departed
        val updated = if (leftBoard) checked.copy(departed = departed + shown.key) else checked
        return updated.planned(followed.map { if (it.sameTrain(current)) current else it }, now)
    }

    /** Il controllo del treno mostrato non è riuscito: si tiene l'ultimo dato. */
    fun failed(message: String?, now: Instant): WatchSnapshot = copy(
        status = WatchStatus.Error,
        updatedAt = now.toEpochMilliseconds(),
        error = message,
        offline = null,
        attemptedAt = 0,
    )

    /**
     * Se [train] oggi è già partito: visto sparire, oppure passato da [WatchPlan.GONE_AFTER] il suo
     * orario. Per il treno mostrato contano il ritardo e l'ultima volta che era in tabellone.
     */
    private fun gone(train: WatchSelection, now: Instant): Boolean {
        if (train.key in departed) return true
        val shown = train.sameTrain(selection)
        val time = (if (shown) this.train?.time else null) ?: train.time ?: return false
        val day = WatchPlan.serviceDay(now)
        val scheduled = WatchPlan.scheduled(time, day) ?: return false
        var end = scheduled + WatchPlan.GONE_AFTER
        val onBoardToday = shown && status == WatchStatus.OnBoard && updatedAt > 0 &&
                WatchPlan.serviceDay(Instant.fromEpochMilliseconds(updatedAt)) == day
        if (onBoardToday) {
            val delay = (this.train?.delayMinutes ?: 0).coerceAtLeast(0).minutes
            end =
                maxOf(end + delay, Instant.fromEpochMilliseconds(updatedAt) + WatchPlan.GONE_AFTER)
        }
        return now > end
    }

    fun encode(): String = WatchJson.encodeToString(serializer(), this)

    companion object {
        /** null se [text] non è uno snapshot leggibile. */
        fun decode(text: String): WatchSnapshot? =
            try {
                WatchJson.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}

/**
 * Richiesta di controllo dal watch al telefono ([WatchPaths.REFRESH]): [request] torna nella
 * risposta, [state] dice al telefono quale treno il watch sta mostrando e quali sono già partiti.
 */
@Serializable
class WatchRefreshRequest(val request: String, val state: WatchSnapshot = WatchSnapshot()) {

    fun encode(): String = WatchJson.encodeToString(serializer(), this)

    companion object {
        /** Una richiesta di un watch con l'app di prima porta solo il numero. */
        fun decode(text: String): WatchRefreshRequest =
            try {
                WatchJson.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                WatchRefreshRequest(text)
            } catch (e: IllegalArgumentException) {
                WatchRefreshRequest(text)
            }
    }
}

/** Come per le impostazioni: un campo nuovo non rompe chi ha una versione precedente dell'app. */
internal val WatchJson = Json { ignoreUnknownKeys = true }

/** I treni seguiti (anche nessuno) in JSON, per mandarli al watch. */
fun encodeWatchTrains(trains: List<WatchSelection>): String =
    WatchJson.encodeToString(WatchSelectionBox.serializer(), WatchSelectionBox(trains = trains))

/**
 * I treni di [encodeWatchTrains], o il solo treno di un telefono con l'app di prima; null se il
 * testo non si legge.
 */
fun decodeWatchTrains(text: String): List<WatchSelection>? =
    try {
        val box = WatchJson.decodeFromString(WatchSelectionBox.serializer(), text)
        box.trains ?: listOfNotNull(box.selection)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

/** Involucro per poter mandare anche "nessun treno"; [selection] è il formato di prima. */
@Serializable
private class WatchSelectionBox(
    val selection: WatchSelection? = null,
    val trains: List<WatchSelection>? = null,
)

/** Percorsi del Wearable Data Layer condivisi tra telefono e watch. */
object WatchPaths {
    /** DataItem dal telefono: i treni seguiti ([encodeWatchTrains]). */
    const val SELECTION = "/watch/selection"

    /** DataItem dal telefono: l'ultimo controllo ([WatchSnapshot.encode]). */
    const val TRAIN = "/watch/train"

    /**
     * Messaggio dal watch: controlla subito i treni e pubblica [TRAIN]. Il contenuto è una
     * [WatchRefreshRequest], il cui numero di richiesta torna in [KEY_REQUEST].
     */
    const val REFRESH = "/watch/refresh"

    /** Chiave del JSON dentro i DataItem. */
    const val KEY_JSON = "json"

    /**
     * Chiave del numero di richiesta in [TRAIN]: lo rende diverso a ogni risposta (un DataItem
     * uguale al precedente non arriverebbe) e dice al watch quale risposta è la sua.
     */
    const val KEY_REQUEST = "request"

    /** Capability dichiarata dall'app telefono (res/values/wear.xml). */
    const val PHONE_CAPABILITY = "departureboard_phone"

    /** Capability dichiarata dall'app watch (res/values/wear.xml). */
    const val WATCH_CAPABILITY = "departureboard_watch"
}
