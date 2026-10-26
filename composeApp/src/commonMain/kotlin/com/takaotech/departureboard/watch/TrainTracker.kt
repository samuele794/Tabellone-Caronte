package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.live.BoardSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Segue i treni dello smartwatch sui monitor partenze delle loro stazioni.
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
     * Controlla i treni seguiti [trains] partendo da quello che il watch sa già ([previous]) e
     * restituisce il nuovo stato, con il treno da mostrare (vedi [WatchPlan]). Si legge il monitor
     * della stazione del treno mostrato e di quelle dei treni scelti solo per numero, che così,
     * appena compaiono, vanno al loro posto nell'ordine; ogni monitor una volta sola. Se il treno
     * mostrato è partito si controlla subito il successivo. Un errore del monitor del treno
     * mostrato finisce nello stato.
     */
    suspend fun track(trains: List<WatchSelection>, previous: WatchSnapshot): WatchSnapshot {
        val now = clock.now()
        var state = previous.planned(trains, now)
        val boards = mutableMapOf<Pair<StationSource, String>, Result<RfiBoard>>()
        suspend fun board(train: WatchSelection): Result<RfiBoard> =
            boards.getOrPut(train.source to train.station.id) {
                try {
                    val client =
                        checkNotNull(sources[train.source]) { "Fornitore ${train.source} non disponibile" }
                    Result.success(client.board(train.station, BoardMode.Departures))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.d(TAG) { "${train.station.name}: monitor non letto (${e.message})" }
                    Result.failure(e)
                }
            }

        val seen = state.plan.filter { it.awaitingDetails }.mapNotNull { train ->
            board(train).getOrNull()?.find(train)?.let(train::completedWith)
        }
        if (seen.isNotEmpty()) state = state.planned(WatchPlan.withDetails(state.plan, seen), now)

        // Ogni giro o si ferma o passa a un altro treno: al più uno per treno seguito.
        repeat(state.plan.size) {
            val shown = state.selection ?: return state
            val next = board(shown).fold(
                onSuccess = { state.observed(it.find(shown), now) },
                onFailure = { state.failed(it.message ?: it::class.simpleName, now) },
            )
            if (next.selection?.sameTrain(shown) == true) return next
            DebugLog.d(TAG) { "Dopo ${shown.label} si segue ${next.selection?.label}" }
            state = next
        }
        return state
    }

    /** [train] com'è adesso in questo monitor; null se non c'è. */
    private fun RfiBoard.find(train: WatchSelection): WatchedTrain? =
        trains.firstOrNull { it.number.trim() == train.trainNumber }
            ?.let { WatchedTrain.of(it, stationName) }

    companion object {
        const val TAG = "Smartwatch"
    }
}
