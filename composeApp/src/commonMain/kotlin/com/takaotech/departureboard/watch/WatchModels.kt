package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Il treno seguito sullo smartwatch: [trainNumber] in partenza da [station]. Vale ogni giorno,
 * finché non se ne sceglie un altro.
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
 * Quello che il watch sa del treno seguito. [train] resta quello dell'ultima volta che il treno
 * era in tabellone, anche quando [status] dice che ora non c'è.
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
) {
    /** Ultimo tentativo, riuscito o no: da qui il watch conta l'attesa per il prossimo. */
    val lastAttemptAt: Long get() = maxOf(updatedAt, attemptedAt)

    /**
     * Questo controllo, tenendo da [previous] dello stesso treno quello visto l'ultima volta se
     * ora non c'è, ed etichetta e orario se questo controllo non li ha.
     */
    fun after(previous: WatchSnapshot?): WatchSnapshot {
        val selection = selection ?: return this
        val known = previous?.selection?.takeIf(selection::sameTrain) ?: return this
        return copy(selection = selection.orDetailsOf(known), train = train ?: previous.train)
    }

    /**
     * La selezione arrivata dal telefono. Un altro treno: i dati del precedente non valgono più.
     * Lo stesso treno: si tiene tutto, compresi etichetta e orario già trovati in tabellone.
     */
    fun withSelection(selection: WatchSelection?): WatchSnapshot = when {
        selection == this.selection -> this
        selection != null && selection.sameTrain(this.selection) -> copy(
            selection = selection.orDetailsOf(
                this.selection
            )
        )

        else -> WatchSnapshot(selection)
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

/** Come per le impostazioni: un campo nuovo non rompe chi ha una versione precedente dell'app. */
internal val WatchJson = Json { ignoreUnknownKeys = true }

/** [selection] (anche nessuna) in JSON, per mandarla al watch. */
fun encodeWatchSelection(selection: WatchSelection?): String =
    WatchJson.encodeToString(WatchSelectionBox.serializer(), WatchSelectionBox(selection))

/** La selezione di [encodeWatchSelection]; null se non ce n'è o non si legge. */
fun decodeWatchSelection(text: String): WatchSelection? =
    try {
        WatchJson.decodeFromString(WatchSelectionBox.serializer(), text).selection
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

/** Involucro per poter mandare anche "nessuna selezione". */
@Serializable
private class WatchSelectionBox(val selection: WatchSelection? = null)

/** Percorsi del Wearable Data Layer condivisi tra telefono e watch. */
object WatchPaths {
    /** DataItem dal telefono: il treno scelto ([encodeWatchSelection]). */
    const val SELECTION = "/watch/selection"

    /** DataItem dal telefono: l'ultimo controllo ([WatchSnapshot.encode]). */
    const val TRAIN = "/watch/train"

    /**
     * Messaggio dal watch: controlla subito il treno e pubblica [TRAIN]. Il contenuto è il numero
     * di richiesta, che torna in [KEY_REQUEST].
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
