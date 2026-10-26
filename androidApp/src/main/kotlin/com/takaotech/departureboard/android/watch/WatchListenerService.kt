package com.takaotech.departureboard.android.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.takaotech.departureboard.android.services
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.watch.WatchPaths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Risponde alle richieste del watch anche ad app chiusa: il sistema avvia il servizio quando
 * arriva il messaggio [WatchPaths.REFRESH]. Il telefono lavora solo quando il watch lo chiede.
 */
class WatchListenerService : WearableListenerService() {

    /**
     * Gira su un thread del servizio, non sul main: si attende qui la fine del controllo, così il
     * sistema non chiude il servizio a metà.
     */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchPaths.REFRESH) return
        val services = services
        runBlocking {
            val done = withTimeoutOrNull(TIMEOUT) {
                services.watchSync.trackAndPublish(
                    services.settingsStore.load().watch,
                    request = event.data.decodeToString(),
                )
            }
            if (done == null) DebugLog.d(PhoneWatchSync.TAG) { "Controllo per il watch scaduto" }
        }
    }

    private companion object {
        val TIMEOUT = 50.seconds
    }
}
