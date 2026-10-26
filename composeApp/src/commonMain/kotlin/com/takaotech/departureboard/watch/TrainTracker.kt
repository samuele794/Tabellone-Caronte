package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.live.BoardSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Segue un treno sul monitor partenze della sua stazione, per lo smartwatch.
 *
 * Usa fornitori suoi, non quelli del tabellone: FNM tiene da parte l'elenco treni di una sola
 * stazione, e seguire un'altra stazione glielo farebbe riscaricare a ogni giro.
 */
class TrainTracker(
    private val sources: Map<StationSource, BoardSource>,
    private val clock: Clock = Clock.System,
) {
    /** Treni in partenza da [station], tra cui scegliere quello da seguire. */
    suspend fun candidates(source: StationSource, station: Station): List<WatchedTrain> {
        val board = sources.getValue(source).board(station, BoardMode.Departures)
        return board.trains.map { WatchedTrain.of(it, board.stationName) }
    }

    /**
     * Il treno di [selection] com'è adesso sul monitor; un errore finisce nello snapshot. Un treno
     * scelto solo per numero, appena compare, porta nello snapshot la selezione completata
     * ([WatchSelection.completedWith]).
     */
    suspend fun track(selection: WatchSelection): WatchSnapshot {
        val train = try {
            val client = checkNotNull(sources[selection.source]) { "Fornitore ${selection.source} non disponibile" }
            val board = client.board(selection.station, BoardMode.Departures)
            board.trains.firstOrNull { it.number.trim() == selection.trainNumber }
                ?.let { WatchedTrain.of(it, board.stationName) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "${selection.label}: controllo fallito (${e.message})" }
            return WatchSnapshot(
                selection = selection,
                status = WatchStatus.Error,
                updatedAt = now(),
                error = e.message ?: e::class.simpleName,
            )
        }
        return WatchSnapshot(
            selection = train?.let(selection::completedWith) ?: selection,
            train = train,
            status = if (train != null) WatchStatus.OnBoard else WatchStatus.NotOnBoard,
            updatedAt = now(),
        )
    }

    private fun now() = clock.now().toEpochMilliseconds()

    companion object {
        const val TAG = "Smartwatch"
    }
}
