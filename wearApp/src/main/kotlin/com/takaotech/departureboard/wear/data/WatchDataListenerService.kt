package com.takaotech.departureboard.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.takaotech.departureboard.wear.wearApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Riceve dal telefono i treni da seguire e i loro controlli anche ad app chiusa, e li salva per
 * app, tile e complication.
 */
class WatchDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Il buffer vale solo dentro questa chiamata: se ne copia il contenuto.
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem.uri.path to DataMapItem.fromDataItem(it.dataItem).dataMap }
        val app = wearApp
        val trainsChanged = runBlocking {
            items.map { (path, map) -> app.refresher.onDataItem(path, map) }.any { it }
        }
        // Treni nuovi: li si chiede subito, senza attendere che qualcuno apra l'app, e si
        // riprogramma il background sui loro orari.
        if (trainsChanged) app.scope.launch {
            app.refresher.refresh(RefreshTrigger.Manual)
            RefreshWorker.schedule(app)
        }
    }
}
