package com.takaotech.departureboard.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.BoardAnnouncements
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Cosa mostra un tabellone: fornitore, stazione e modalità. */
data class BoardTarget(val source: StationSource, val station: Station, val mode: BoardMode)

/** Voce che annuncia le variazioni di un tabellone. */
internal interface BoardVoice {
    /**
     * Annuncia [news]; false se non lo fa (annunci spenti o silenziati), e allora le righe
     * lampeggiano subito. Quelle annunciate lampeggiano quando comincia il jingle: [BoardFeed.blink].
     */
    fun announce(news: List<Announcement>): Boolean

    /** Gli annunci in coda o in corso non servono più. */
    fun clear()
}

/**
 * I dati di un tabellone e il loro aggiornamento: righe, avvisi, stato del download, conto alla
 * rovescia e lampeggio delle righe che cambiano. Lo usano il tabellone a tutto schermo e le schede
 * delle stazioni sulla mappa; [target] dice cosa scaricare ed è letto a ogni aggiornamento,
 * [blinkDuration] quanto lampeggia una riga. Con una [voice] le variazioni si annunciano,
 * altrimenti le loro righe lampeggiano subito.
 */
@Stable
class BoardFeed internal constructor(
    private val sources: Map<StationSource, BoardSource>,
    private val target: () -> BoardTarget,
    private val blinkDuration: () -> Duration,
    private val voice: BoardVoice? = null,
) {
    private val mapper = RfiMapper()
    private val announcements = BoardAnnouncements()

    var departures by mutableStateOf<List<Departure>>(emptyList())
        private set
    var notices by mutableStateOf<List<String>>(emptyList())
        private set
    var updatedAt by mutableStateOf<LocalDateTime?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Prossimo riscaricamento dell'elenco treni, per i fornitori che lo fanno più di rado. */
    var nextListRefreshAt by mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null)
        private set

    /** Stazione e modalità a cui si riferiscono [departures]. */
    private var loadedKey: Triple<StationSource, String, BoardMode>? = null

    /** Fine dell'ultimo tentativo di aggiornamento, riuscito o no. */
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    /** Quando partirà il prossimo aggiornamento; null finché non è in programma. */
    var nextRefreshAt by mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null)
        private set

    /** Aggiornamenti chiesti a mano, in attesa che [waitForRefresh] li raccolga. */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    /** Nessun download in corso: si può chiedere un aggiornamento a mano. */
    val canRefreshNow get() = !loading

    /** Il prossimo [refresh] è chiesto a mano: riscarica anche l'elenco dei treni. */
    private var manualRefresh = false

    /** Cresce a ogni [clear]: un tabellone chiesto prima arriva già da buttare. */
    private var generation = 0

    /**
     * Aggiorna subito, elenco dei treni compreso: [waitForRefresh] smette di attendere e il conto
     * alla rovescia riparte.
     */
    fun requestRefresh() {
        if (!canRefreshNow) return
        manualRefresh = true
        refreshRequests.trySend(Unit)
    }

    /**
     * Attende [after] prima del prossimo aggiornamento, rendendolo visibile in [nextRefreshAt];
     * finisce prima se nel frattempo arriva [requestRefresh].
     */
    suspend fun waitForRefresh(after: Duration) {
        nextRefreshAt = TimeSource.Monotonic.markNow() + after
        withTimeoutOrNull(after) { refreshRequests.receive() }
    }

    /**
     * Attesa prima del prossimo aggiornamento: zero se i dati sono di un'altra tabella, altrimenti
     * il resto di [interval]. Serve quando il tabellone torna a schermo (es. dopo una rotazione).
     */
    fun nextRefreshIn(interval: Duration): Duration {
        val last = lastRefresh ?: return Duration.ZERO
        val target = target()
        if (loadedKey != Triple(target.source, target.station.id, target.mode)) return Duration.ZERO
        return (interval - last.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** Da quando il tabellone non è visibile; null se lo è. */
    private var pausedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Il tabellone non è più visibile: si zittisce l'annuncio in corso. Se la pausa dura più di
     * [STALE_PAUSE], il primo tabellone al ritorno fa solo da base, così le variazioni accumulate
     * nel frattempo non si annunciano in blocco (una rotazione invece non perde nulla).
     */
    fun pause() {
        voice?.clear()
        pausedAt = TimeSource.Monotonic.markNow()
    }

    /**
     * Butta i dati: al prossimo aggiornamento si riscarica tutto e il primo tabellone fa da nuova
     * base per le variazioni. Un aggiornamento già in corso arriverà da buttare.
     */
    fun clear() {
        generation++
        departures = emptyList()
        notices = emptyList()
        updatedAt = null
        error = null
        loadedKey = null
        lastRefresh = null
        nextRefreshAt = null
        nextListRefreshAt = null
        voice?.clear()
        announcements.reset()
    }

    /** Scarica il tabellone; se fallisce tiene gli ultimi dati validi della stessa tabella. */
    suspend fun refresh() {
        val target = target()
        val key = Triple(target.source, target.station.id, target.mode)
        val client = sources.getValue(target.source)
        if (key != loadedKey) {
            departures = emptyList()
            notices = emptyList()
            nextListRefreshAt = null
            // Gli annunci della stazione precedente non servono più.
            voice?.clear()
        }
        loading = true
        // Una richiesta arrivata mentre l'attesa era già finita la soddisfa questo aggiornamento.
        refreshRequests.tryReceive()
        if (manualRefresh) {
            manualRefresh = false
            client.invalidateList()
        }
        val generation = generation
        var cancelled = false
        try {
            val board = client.board(target.station, target.mode)
            // Arrivato dopo clear() (es. aprendo la mappa): è già da buttare.
            if (generation != this.generation) return
            departures = mapper.departures(board)
            notices = board.notices
            if (pausedAt?.let { it.elapsedNow() > STALE_PAUSE } == true) announcements.reset()
            pausedAt = null
            // Il primo tabellone di una stazione fa solo da base: update() non annuncia nulla.
            val news = announcements.update(key.second, key.third, board)
            // Senza voce le righe lampeggiano subito, tutte insieme.
            if (news.isNotEmpty() && voice?.announce(news) != true) {
                blink(news.map { it.train.number }.distinct())
            }
            loadedKey = key
            updatedAt = localNow()
            error = null
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            error = "Aggiornamento non riuscito: ${e.message ?: e::class.simpleName}"
        } finally {
            loading = false
            if (generation == this.generation) {
                // Un aggiornamento interrotto (es. intervallo cambiato) non rimanda il prossimo.
                if (!cancelled) lastRefresh = TimeSource.Monotonic.markNow()
                nextListRefreshAt = client.nextListRefreshAt(target.station)
            }
        }
    }

    /** L'intervallo dell'elenco treni è cambiato: il suo conto alla rovescia si sposta subito. */
    internal fun listRefreshChanged() {
        if (nextListRefreshAt == null) return
        val target = target()
        nextListRefreshAt = sources.getValue(target.source).nextListRefreshAt(target.station)
    }

    /** Lampeggi in corso per numero di treno: più lampeggi dello stesso treno si accavallano. */
    private var blinkCounts by mutableStateOf<Map<String, Int>>(emptyMap())

    /** Numeri dei treni annunciati la cui riga lampeggia in questo momento. */
    val blinkingTrains: Set<String> get() = blinkCounts.keys

    /** Treni da far lampeggiare, raccolti da [runBlinks]. */
    private val blinkRequests = Channel<List<String>>(Channel.UNLIMITED)

    /** Fa lampeggiare le righe dei treni [numbers], se gira [runBlinks]. */
    fun blink(numbers: List<String>) {
        blinkRequests.trySend(numbers)
    }

    /** Fa lampeggiare per [blinkDuration] le righe chieste con [blink]. Gira finché non viene cancellata. */
    suspend fun runBlinks(): Unit = coroutineScope {
        try {
            for (numbers in blinkRequests) {
                val duration = blinkDuration()
                numbers.forEach { number -> launch { blink(number, duration) } }
            }
        } finally {
            blinkCounts = emptyMap()
        }
    }

    private suspend fun blink(number: String, duration: Duration) {
        blinkCounts += number to (blinkCounts[number] ?: 0) + 1
        try {
            delay(duration)
        } finally {
            val left = (blinkCounts[number] ?: 1) - 1
            blinkCounts = if (left > 0) blinkCounts + (number to left) else blinkCounts - number
        }
    }

    /** [departures] con le righe dei treni annunciati ([blinkingTrains]) che lampeggiano. */
    val boardDepartures: List<Departure>
        get() = blinkingTrains.takeIf { it.isNotEmpty() }?.let { numbers ->
            departures.map { if (it.number in numbers) it.copy(blink = true) else it }
        } ?: departures

    companion object {
        /** Pausa oltre la quale gli annunci ripartono da una nuova base. */
        val STALE_PAUSE = 1.minutes
    }
}
