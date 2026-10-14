package fnm

import cache.StationSource
import debuglog.DebugLog
import fnm.FnmBoardSource.Companion.LIST_REFRESH
import fnm.FnmBoardSource.Companion.MAX_TRAINS
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
import live.BoardSource
import rfi.BoardMode
import rfi.RfiBoard
import rfi.Station
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Tabellone FNM da trenord.it.
 *
 * L'elenco dei treni della stazione (`station-details`) è lento e cambia solo con l'orario, quindi
 * si riscarica ogni [LIST_REFRESH]; a ogni [board] si chiedono invece i dettagli dei primi
 * [MAX_TRAINS] treni (ritardo, binario, soppressione): dal BFF, o se non va dall'HTML.
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

    private var lists: FnmStationLists? = null
    private var listStation: String? = null
    private var listFetchedAt: Instant? = null

    override var nextListRefreshAt: TimeSource.Monotonic.ValueTimeMark? = null
        private set

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

        val trains = ArrayList<rfi.RfiTrain>()
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

    /** Elenco della stazione: lo stesso per [LIST_REFRESH], se riscaricarlo fallisce si tiene il vecchio. */
    private suspend fun stationLists(station: Station): FnmStationLists {
        val now = clock.now()
        val cached = lists?.takeIf { listStation == station.id }
        val fetchedAt = listFetchedAt
        if (cached != null && fetchedAt != null && now - fetchedAt < LIST_REFRESH) return cached
        if (listStation != station.id) alive.clear()

        val fresh = try {
            client.stationDetails(station.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) throw e
            DebugLog.d(TAG) { "Elenco ${station.name} non aggiornato (${e.message}), uso il precedente" }
            return cached
        }
        lists = fresh
        listStation = station.id
        listFetchedAt = now
        nextListRefreshAt = TimeSource.Monotonic.markNow() + LIST_REFRESH
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

        /** Ogni quanto si riscarica l'elenco dei treni della stazione. */
        val LIST_REFRESH = 5.minutes

        private const val PARALLEL_REQUESTS = 6
        private const val HALF_DAY = 12 * 3600

        val MilanoCadorna = Station("S01066", "MILANO CADORNA")
    }
}
