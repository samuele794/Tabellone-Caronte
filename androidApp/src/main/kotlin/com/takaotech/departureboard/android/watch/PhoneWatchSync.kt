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
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchSync
import com.takaotech.departureboard.watch.encodeWatchSelection
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
 * Lo smartwatch visto dal telefono, con il Wearable Data Layer: il treno scelto e i suoi
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

    private val completedState = MutableStateFlow<WatchSelection?>(null)
    override val completed = completedState.asStateFlow()

    /** Ultima selezione da mandare; null finché non ce n'è una (anche "nessun treno"). */
    private val pending = MutableStateFlow<Pending?>(null)

    private class Pending(val selection: WatchSelection?) {
        override fun equals(other: Any?) = other is Pending && other.selection == selection
        override fun hashCode() = selection.hashCode()
    }

    private val tracking = Mutex()
    private var lastTracked: Pair<TimeSource.Monotonic.ValueTimeMark, WatchSnapshot>? = null

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
        // Una selezione alla volta, e solo l'ultima se nel frattempo è cambiata di nuovo.
        scope.launch {
            pending.filterNotNull().collect { put(WatchPaths.SELECTION, encodeWatchSelection(it.selection)) }
        }
    }

    private fun onCapabilityChanged(info: CapabilityInfo) {
        connectedState.value = info.nodes.isNotEmpty()
    }

    override fun selectionChanged(selection: WatchSelection?) {
        pending.value = Pending(selection)
    }

    override suspend fun candidates(source: StationSource, station: Station) =
        tracker.candidates(source, station)

    /**
     * Controlla il treno salvato nelle impostazioni e manda l'esito al watch. App, tile e
     * complication del watch possono chiederlo quasi insieme: entro [REUSE] si rimanda il
     * controllo appena fatto. [request] è il numero di richiesta del watch, che torna nel DataItem.
     */
    suspend fun trackAndPublish(request: String) {
        val selection = settingsStore.load().watch
        val snapshot = if (selection == null) {
            WatchSnapshot()
        } else {
            tracking.withLock {
                lastTracked?.takeIf { (at, last) -> selection.sameTrain(last.selection) && at.elapsedNow() < REUSE }?.second
                    ?: tracker.track(selection).also { lastTracked = TimeSource.Monotonic.markNow() to it }
            }
        }
        put(WatchPaths.TRAIN, snapshot.encode(), request)
        val seen = snapshot.selection
        if (selection != null && seen != null && selection.orDetailsOf(seen) != selection) {
            trainSeen(selection, seen)
        }
    }

    /**
     * Il treno [chosen], scelto solo per numero, è comparso in tabellone: etichetta e orario di
     * [seen] vanno nelle impostazioni, al tabellone se è aperto ([completed]) e al watch.
     */
    private suspend fun trainSeen(chosen: WatchSelection, seen: WatchSelection) {
        DebugLog.d(TAG) { "${chosen.label} trovato in tabellone: ${seen.label}" }
        completedState.value = seen
        selectionChanged(seen)
        settingsStore.update { if (it.watch == chosen) it.copy(watch = seen) else it }
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
