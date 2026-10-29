package com.takaotech.departureboard.fnm

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.fnm.FnmBoardSource.Companion.MAX_STATIONS
import com.takaotech.departureboard.live.BoardSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Tabellone FNM da trenord.it.
 *
 * L'elenco dei treni della stazione (`station-details`) è lento e cambia solo con l'orario, quindi
 * si riscarica ogni [listRefresh] (predefinito [LIST_REFRESH]); a ogni [board] si chiedono invece
 * i dettagli dei primi [MAX_TRAINS] treni (ritardo, binario, soppressione): dal BFF, o se non va
 * dall'HTML.
 *
 * L'elenco toglie i treni all'orario programmato anche se sono in ritardo: quelli che secondo
 * l'ultimo dettaglio non sono ancora partiti restano finché il dettaglio non li dà per partiti.
 */
class FnmBoardSource(
    private val client: FnmClient,
    private val keys: FnmKeyStore,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.of("Europe/Rome"),
) : BoardSource {

    override val source = StationSource.Fnm
    override val defaultStation = MilanoCadorna

    /** Elenco dei treni di una stazione e quando è stato scaricato; [fetchedAt] null = da riscaricare. */
    private data class StationList(
        val lists: FnmStationLists,
        val fetchedAt: Instant?,
        val mark: TimeSource.Monotonic.ValueTimeMark,
    )

    /**
     * Elenchi per codice stazione, dal meno al più recente: il tabellone e le schede sulla mappa
     * ne chiedono di diverse, e non devono riscaricarseli a vicenda. Al più [MAX_STATIONS].
     */
    private val lists = LinkedHashMap<String, StationList>()

    override var listRefresh: Duration? = LIST_REFRESH
        set(value) {
            field = value ?: LIST_REFRESH
        }

    private val listInterval get() = listRefresh ?: LIST_REFRESH

    override fun nextListRefreshAt(station: Station): TimeSource.Monotonic.ValueTimeMark? =
        lists[station.id]?.mark?.plus(listInterval)

    /** Se riscaricarli fallisce, si tengono comunque gli elenchi precedenti. */
    override fun invalidateList() {
        lists.entries.forEach { it.setValue(it.value.copy(fetchedAt = null)) }
    }

    /** Treni ancora in corsa secondo l'ultimo dettaglio, per stazione e modalità. */
    private val alive = HashMap<Pair<String, BoardMode>, Map<String, FnmListTrain>>()

    private val requests = Semaphore(PARALLEL_REQUESTS)

    override suspend fun stations() = client.stations()

    override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
        val now = clock.now().toLocalDateTime(timeZone)
        val lists = stationLists(station)
        val key = station.id to mode
        val fresh = if (mode == BoardMode.Departures) lists.departures else lists.arrivals
        val retained =
            alive[key].orEmpty().values.filter { kept -> fresh.none { it.code == kept.code } }
        var remaining = (fresh + retained).distinctBy { it.code }
            .sortedBy {
                if (it.time.isBlank()) Int.MAX_VALUE else FnmMapper.minutesUntil(
                    now.time,
                    it.time
                )
            }

        val trains = ArrayList<com.takaotech.departureboard.rfi.RfiTrain>()
        val stillAlive = LinkedHashMap<String, FnmListTrain>()
        // Se qualche treno risulta già partito, si completa la tabella con i successivi.
        while (trains.size < MAX_TRAINS && remaining.isNotEmpty()) {
            val batch = remaining.take(MAX_TRAINS - trains.size)
            remaining = remaining.drop(batch.size)
            val details = details(batch, now)
            for (row in batch) {
                val detail = details[row.code]
                val train = FnmMapper.train(row, detail, station, mode, now.time) ?: continue
                trains += train
                if (detail != null) stillAlive[row.code] = row
            }
        }
        alive[key] = stillAlive
        DebugLog.d(TAG) {
            "${station.name} ${mode.name}: ${trains.size} treni, dettagli ${stillAlive.size}, " +
                    "trattenuti ${retained.size}"
        }
        return RfiBoard(
            stationName = station.name,
            mode = mode,
            trains = trains,
            notices = trains.flatMap { it.notes }.distinct(),
        )
    }

    /** Elenco della stazione: lo stesso per [listRefresh], se riscaricarlo fallisce si tiene il vecchio. */
    private suspend fun stationLists(station: Station): FnmStationLists {
        val now = clock.now()
        // Rimesso in fondo: è il più recente.
        val cached = lists.remove(station.id)?.also { lists[station.id] = it }
        val fetchedAt = cached?.fetchedAt
        if (fetchedAt != null && now - fetchedAt < listInterval) return cached.lists

        val fresh = try {
            client.stationDetails(station.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) throw e
            DebugLog.d(TAG) { "Elenco ${station.name} non aggiornato (${e.message}), uso il precedente" }
            return cached.lists
        }
        lists.remove(station.id)
        lists[station.id] = StationList(fresh, now, TimeSource.Monotonic.markNow())
        while (lists.size > MAX_STATIONS) {
            val oldest = lists.keys.first()
            lists.remove(oldest)
            alive.keys.removeAll { it.first == oldest }
        }
        return fresh
    }

    private suspend fun details(
        batch: List<FnmListTrain>,
        now: LocalDateTime
    ): Map<String, FnmTrainDetail?> =
        coroutineScope {
            batch.map { row -> async { requests.withPermit { row.code to detail(row, now) } } }
                .awaitAll().toMap()
        }

    /** Dal BFF; se non risponde, non si decifra o non ha il treno, dall'HTML; altrimenti null. */
    private suspend fun detail(row: FnmListTrain, now: LocalDateTime): FnmTrainDetail? {
        try {
            val body = keys.decrypt(client.trainBff(row.number, serviceDate(row.time, now)))
            FnmParser.bffDetail(FnmParser.parseBff(body), row.code)?.let { return it }
            DebugLog.d(TAG) { "BFF senza il treno ${row.code}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "BFF ${row.code}: ${e.message ?: e::class.simpleName}" }
        }
        return try {
            client.trainSubDetails(row.number)[row.code]?.let(FnmParser::parseSubDetail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "Dettaglio HTML ${row.code}: ${e.message ?: e::class.simpleName}" }
            null
        }
    }

    /** Giorno del treno: quello di oggi, salvo orari a cavallo della mezzanotte. */
    private fun serviceDate(time: String, now: LocalDateTime): LocalDate {
        val t = FnmMapper.seconds(time) ?: return now.date
        val raw = t - now.time.toSecondOfDay()
        return when {
            raw < -HALF_DAY -> now.date.plus(DatePeriod(days = 1))
            raw >= HALF_DAY -> now.date.minus(DatePeriod(days = 1))
            else -> now.date
        }
    }

    override fun close() {
        keys.close()
        client.close()
    }

    companion object {
        const val TAG = "Fnm"

        /** Treni di cui si chiede il dettaglio, e quindi righe mostrate al più. */
        const val MAX_TRAINS = 15

        /** Ogni quanto si riscarica l'elenco dei treni della stazione, se non scelto altrimenti. */
        val LIST_REFRESH = 5.minutes

        /** Stazioni di cui si tiene l'elenco: il tabellone e le schede aperte sulla mappa. */
        private const val MAX_STATIONS = 4

        private const val PARALLEL_REQUESTS = 6
        private const val HALF_DAY = 12 * 3600

        val MilanoCadorna = Station("S01066", "MILANO CADORNA")
    }
}
