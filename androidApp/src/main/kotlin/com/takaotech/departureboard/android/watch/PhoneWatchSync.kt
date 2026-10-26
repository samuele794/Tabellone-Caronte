package com.takaotech.departureboard.android.watch

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.live.SettingsStore
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.TrainTracker
import com.takaotech.departureboard.watch.WatchPaths
import com.takaotech.departureboard.watch.WatchPlan
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchSync
import com.takaotech.departureboard.watch.encodeWatchTrains
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Lo smartwatch visto dal telefono, con il Wearable Data Layer: i treni scelti e i loro
 * controlli vanno al watch come DataItem, che il sistema consegna appena il watch è raggiungibile.
 *
 * Senza Google Play Services o senza app Wear OS le chiamate falliscono: il watch risulta
 * semplicemente non collegato.
 */
class PhoneWatchSync(
    context: Context,
    private val tracker: TrainTracker,
    private val settingsStore: SettingsStore,
) : WatchSync {

    private val dataClient = Wearable.getDataClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectedState = MutableStateFlow(false)
    override val connected = connectedState.asStateFlow()

    private val completedState = MutableStateFlow<List<WatchSelection>>(emptyList())
    override val completed = completedState.asStateFlow()

    /** Ultimi treni da mandare; null finché non ce ne sono (anche nessuno). */
    private val pending = MutableStateFlow<List<WatchSelection>?>(null)

    private val tracking = Mutex()

    /** Ultimo controllo, con i treni per cui è stato fatto. */
    private var lastTracked: Triple<TimeSource.Monotonic.ValueTimeMark, List<WatchSelection>, WatchSnapshot>? =
        null

    init {
        capabilityClient.addListener(::onCapabilityChanged, WatchPaths.WATCH_CAPABILITY)
        scope.launch {
            try {
                val info = capabilityClient
                    .getCapability(WatchPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
                onCapabilityChanged(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.d(TAG) { "Smartwatch non disponibili (${e.message})" }
            }
        }
        // Un elenco alla volta, e solo l'ultimo se nel frattempo è cambiato di nuovo.
        scope.launch {
            pending.filterNotNull().collect { put(WatchPaths.SELECTION, encodeWatchTrains(it)) }
        }
    }

    private fun onCapabilityChanged(info: CapabilityInfo) {
        connectedState.value = info.nodes.isNotEmpty()
    }

    override fun trainsChanged(trains: List<WatchSelection>) {
        pending.value = trains
    }

    override suspend fun candidates(source: StationSource, station: Station) =
        tracker.candidates(source, station)

    /**
     * Controlla i treni salvati nelle impostazioni, partendo dallo stato del watch ([state]: il
     * treno che mostra e quelli già partiti), e manda l'esito al watch. App, tile e complication
     * del watch possono chiederlo quasi insieme: entro [REUSE] si rimanda il controllo appena
     * fatto. [request] è il numero di richiesta del watch, che torna nel DataItem.
     */
    suspend fun trackAndPublish(request: String, state: WatchSnapshot) {
        val trains = settingsStore.load().followedTrains
        val snapshot = if (trains.isEmpty()) {
            WatchSnapshot()
        } else {
            tracking.withLock {
                lastTracked?.takeIf { (at, tracked) -> tracked == trains && at.elapsedNow() < REUSE }?.third
                    ?: tracker.track(trains, state).also {
                        lastTracked = Triple(TimeSource.Monotonic.markNow(), trains, it)
                    }
            }
        }
        put(WatchPaths.TRAIN, snapshot.encode(), request)
        val completed = WatchPlan.withDetails(trains, snapshot.plan)
        if (completed != trains) trainsSeen(trains, completed)
    }

    /**
     * Treni di [chosen], scelti solo per numero, comparsi in tabellone: etichetta, orario e ordine
     * di [found] vanno nelle impostazioni, al tabellone se è aperto ([completed]) e al watch.
     */
    private suspend fun trainsSeen(chosen: List<WatchSelection>, found: List<WatchSelection>) {
        DebugLog.d(TAG) {
            "Trovati in tabellone: ${
                found.filterNot(chosen::contains).map { it.label }
            }"
        }
        completedState.value = found
        trainsChanged(found)
        settingsStore.update {
            if (it.followedTrains == chosen) it.copy(watch = null, watchTrains = found) else it
        }
    }

    /** Pubblica [json] come DataItem urgente su [path]: il watch lo riceve subito, non a lotti. */
    private suspend fun put(path: String, json: String, requestId: String? = null) {
        try {
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putString(WatchPaths.KEY_JSON, json)
                requestId?.let { dataMap.putString(WatchPaths.KEY_REQUEST, it) }
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "$path non pubblicato (${e.message})" }
        }
    }

    companion object {
        const val TAG = "Smartwatch"

        /** Per quanto un controllo vale anche per le richieste che arrivano subito dopo. */
        val REUSE = 10.seconds
    }
}
