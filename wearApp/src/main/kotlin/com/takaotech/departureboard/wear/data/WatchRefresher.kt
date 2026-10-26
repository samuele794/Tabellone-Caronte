package com.takaotech.departureboard.wear.data

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.fnm.FnmBoardSource
import com.takaotech.departureboard.fnm.FnmClient
import com.takaotech.departureboard.fnm.FnmKeyStore
import com.takaotech.departureboard.rfi.RfiBoardSource
import com.takaotech.departureboard.rfi.RfiClient
import com.takaotech.departureboard.watch.TrainTracker
import com.takaotech.departureboard.watch.WatchPaths
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStore
import com.takaotech.departureboard.watch.decodeWatchSelection
import com.takaotech.departureboard.wear.complication.TrainComplicationService
import com.takaotech.departureboard.wear.net.WatchNetwork
import com.takaotech.departureboard.wear.tile.TrainTileService
import com.takaotech.departureboard.wear.wearApp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tiene aggiornato il [WatchStore] del watch, in modo ibrido:
 * 1. se il telefono è raggiungibile gli si chiede il controllo ([WatchPaths.REFRESH]) e si attende
 *    il DataItem di risposta;
 * 2. altrimenti, se il watch ha davvero internet ([WatchNetwork]), si controlla da qui;
 * 3. altrimenti resta l'ultimo dato, segnato con il motivo ([WatchSnapshot.offline]).
 *
 * La selezione arriva solo dal telefono ([WatchPaths.SELECTION]): qui non si sceglie nulla.
 */
class WatchRefresher(private val context: Context, val store: WatchStore) {

    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val network = WatchNetwork(context)
    private val refreshing = Mutex()

    private val busy = MutableStateFlow(false)

    /** Un aggiornamento è in corso. */
    val refreshingNow = busy.asStateFlow()

    /** Client FNM della rete in uso, per riscaricare la chiave del BFF se serve. */
    private var fnmClient: FnmClient? = null
    private val fnmKeys = FnmKeyStore(
        File(context.filesDir, "datastore/fnm-key.json").absolutePath,
        fetchJs = { checkNotNull(fnmClient) { "Nessuna rete per la chiave Trenord" }.webComponentsJs() },
    )

    /** Aggiorna il treno seguito; uno solo alla volta, chi arriva durante attende quello in corso. */
    suspend fun refresh() {
        if (refreshing.isLocked) {
            refreshing.withLock { }
            return
        }
        refreshing.withLock {
            busy.value = true
            try {
                val selection = store.load().selection ?: return
                if (askPhone()) return
                val outcome = network.withInternet(probeUrl(selection.source)) { http -> direct(selection, http) }
                when (outcome) {
                    is WatchNetwork.Outcome.Online -> applyTrain(outcome.value)
                    is WatchNetwork.Outcome.Offline -> save { latest ->
                        if (latest.selection == selection) latest.copy(offline = outcome.reason) else latest
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Per tile e complication, che devono rispondere subito: se [snapshot] è più vecchio di
     * [STALE_AFTER] fa partire un aggiornamento in background, che arrivando le ridisegna.
     */
    fun refreshIfStale(snapshot: WatchSnapshot) {
        if (snapshot.selection == null || busy.value) return
        if (System.currentTimeMillis() - snapshot.updatedAt < STALE_AFTER.inWholeMilliseconds) return
        context.wearApp.scope.launch { refresh() }
    }

    /**
     * Chiede il controllo al telefono e ne attende la risposta; false se nessun telefono con l'app
     * è raggiungibile o non risponde in tempo.
     */
    private suspend fun askPhone(): Boolean {
        val nodes = try {
            capabilityClient.getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await().nodes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "Telefono non cercabile (${e.message})" }
            return false
        }
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return false
        // Il numero di richiesta torna nel DataItem: così cambia sempre e la risposta si riconosce.
        val id = Random.nextLong().toString()
        val reply = CompletableDeferred<WatchSnapshot?>()
        val listener = DataClient.OnDataChangedListener { events ->
            events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchPaths.TRAIN }
                .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
                .firstOrNull { it.getString(WatchPaths.KEY_REQUEST) == id }
                ?.let { reply.complete(it.snapshot()) }
        }
        return try {
            dataClient.addListener(listener).await()
            messageClient.sendMessage(phone.id, WatchPaths.REFRESH, id.encodeToByteArray()).await()
            val snapshot = withTimeoutOrNull(PHONE_TIMEOUT) { reply.await() }
            snapshot?.let { applyTrain(it) }
            snapshot != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "Telefono non raggiunto (${e.message})" }
            false
        } finally {
            dataClient.removeListener(listener)
        }
    }

    /** Controllo da qui, sulla rete del watch. */
    private suspend fun direct(selection: WatchSelection, http: HttpClient): WatchSnapshot {
        val rfi = RfiClient(httpClient = http)
        val fnm = FnmClient(httpClient = http)
        fnmClient = fnm
        try {
            // FnmBoardSource non si chiude: chiuderebbe anche la chiave, che resta per la prossima volta.
            val source = when (selection.source) {
                StationSource.Rfi -> RfiBoardSource(rfi)
                StationSource.Fnm -> FnmBoardSource(fnm, fnmKeys)
            }
            return TrainTracker(mapOf(selection.source to source)).track(selection)
        } finally {
            fnmClient = null
            rfi.close()
            fnm.close()
        }
    }

    /** Legge i DataItem già sincronizzati: all'avvio l'app non riparte da vuota. */
    suspend fun syncFromPhone() {
        try {
            val items = dataClient.dataItems.await()
            try {
                val maps = items.associate { it.uri.path to DataMapItem.fromDataItem(it).dataMap }
                maps[WatchPaths.SELECTION]?.let { applySelection(it) }
                maps[WatchPaths.TRAIN]?.snapshot()?.let { applyTrain(it) }
            } finally {
                items.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "DataItem non letti (${e.message})" }
        }
    }

    /** Un DataItem arrivato dal telefono; true se ha cambiato il treno da seguire. */
    suspend fun onDataItem(path: String?, map: DataMap): Boolean = when (path) {
        WatchPaths.SELECTION -> applySelection(map)
        WatchPaths.TRAIN -> {
            map.snapshot()?.let { applyTrain(it) }
            false
        }

        else -> false
    }

    private suspend fun applySelection(map: DataMap): Boolean {
        val selection = map.getString(WatchPaths.KEY_JSON)?.let(::decodeWatchSelection)
        var changed = false
        save { latest ->
            changed = latest.selection != selection
            latest.withSelection(selection)
        }
        return changed
    }

    /**
     * Un controllo, dal telefono o da qui. Vale solo per la selezione attuale: un DataItem vecchio
     * di un altro treno non deve riportarla indietro.
     */
    private suspend fun applyTrain(snapshot: WatchSnapshot) = save { latest ->
        if (snapshot.selection == latest.selection) snapshot.after(latest) else latest
    }

    private suspend fun save(transform: (WatchSnapshot) -> WatchSnapshot) {
        store.update(transform)
        updateSurfaces()
    }

    /** Tile e complication rileggono lo store. */
    private fun updateSurfaces() {
        TileService.getUpdater(context).requestUpdate(TrainTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, TrainComplicationService::class.java))
            .requestUpdateAll()
    }

    private fun DataMap.snapshot(): WatchSnapshot? = getString(WatchPaths.KEY_JSON)?.let(WatchSnapshot::decode)

    private fun probeUrl(source: StationSource) = when (source) {
        StationSource.Rfi -> RfiClient.DEFAULT_BASE_URL
        StationSource.Fnm -> FnmClient.DEFAULT_BASE_URL
    }

    companion object {
        const val TAG = "Smartwatch"

        /** Età oltre la quale tile e complication chiedono un aggiornamento. */
        val STALE_AFTER = 4.minutes

        /** Quanto attendere la risposta del telefono prima di provare da soli. */
        val PHONE_TIMEOUT = 30.seconds
    }
}
