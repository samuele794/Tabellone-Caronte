package com.takaotech.departureboard.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import com.takaotech.departureboard.watch.WatchedTrain
import com.takaotech.departureboard.wear.data.ActiveSurfaces
import com.takaotech.departureboard.wear.data.RefreshWorker
import com.takaotech.departureboard.wear.ui.TrainActivity
import com.takaotech.departureboard.wear.wearApp
import kotlinx.coroutines.launch

/**
 * Il treno seguito sul quadrante: breve "19:18" con "+5'", lunga "R 91831 VIGNOLA 19:18 +5' BIN 7".
 * Il sistema non la chiede a intervalli: la si ridisegna solo quando il treno cambia, e finché è
 * sul quadrante [RefreshWorker] aggiorna il treno attorno alla partenza.
 */
class TrainComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = wearApp
        // Complication aggiunte prima che ci fosse l'attivazione: si segnano alla prima richiesta.
        if (app.surfaces.add(ActiveSurfaces.complication(request.complicationInstanceId))) {
            RefreshWorker.schedule(app)
        }
        return data(request.complicationType, app.refresher.store.load())
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        val app = wearApp
        if (app.surfaces.add(ActiveSurfaces.complication(complicationInstanceId))) {
            app.scope.launch { RefreshWorker.schedule(app) }
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        val app = wearApp
        app.surfaces.remove(ActiveSurfaces.complication(complicationInstanceId))
        app.scope.launch { RefreshWorker.schedule(app) }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, Preview)

    private fun data(type: ComplicationType, snapshot: WatchSnapshot): ComplicationData? {
        val train = snapshot.train.takeIf { snapshot.status == WatchStatus.OnBoard }
        val description = text(longText(snapshot, train))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(shortText(train)), description)
                .setTitle(text(train?.time ?: "--:--"))
                .setTapAction(openApp())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(longText(snapshot, train)), description)
                .setTapAction(openApp())
                .build()

            else -> null
        }
    }

    /** Ritardo in breve: "+5'", "OK", "CANC"; "--" se il treno non è in tabellone. */
    private fun shortText(train: WatchedTrain?): String = when {
        train == null -> "--"
        train.cancelled -> "CANC"
        (train.delayMinutes ?: 0) > 0 -> "+${train.delayMinutes}'"
        else -> "OK"
    }

    private fun longText(snapshot: WatchSnapshot, train: WatchedTrain?): String {
        val selection = snapshot.selection ?: return "Nessun treno"
        if (train == null) return "${selection.label} · in attesa"
        return listOfNotNull(
            "${train.category} ${train.number}",
            train.destination,
            train.time,
            shortText(train).takeIf { it != "OK" },
            train.platform.takeIf { it.isNotBlank() }?.let { "BIN $it" },
        ).joinToString(" ")
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, TrainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** Treno d'esempio per l'anteprima nel selettore dei quadranti. */
        val Preview = WatchSnapshot(
            selection = WatchSelection(
                StationSource.Rfi,
                Station("837", "BOLOGNA CENTRALE"),
                "91831",
                "R 91831 → VIGNOLA",
            ),
            train = WatchedTrain(
                carrier = "TRENITALIA TPER",
                category = "R",
                number = "91831",
                destination = "VIGNOLA",
                time = "19:18",
                delayMinutes = 5,
                platform = "7",
            ),
            status = WatchStatus.OnBoard,
        )
    }
}
