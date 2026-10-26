package com.takaotech.departureboard.wear.tile

import androidx.annotation.StringRes
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.wear.R
import com.takaotech.departureboard.wear.data.ActiveSurfaces
import com.takaotech.departureboard.wear.data.RefreshWorker
import com.takaotech.departureboard.wear.ui.TrainActivity
import com.takaotech.departureboard.wear.ui.delayText
import com.takaotech.departureboard.wear.ui.liveTrain
import com.takaotech.departureboard.wear.ui.tileStatusText
import com.takaotech.departureboard.wear.wearApp
import kotlinx.coroutines.launch

/**
 * Tile del treno seguito: destinazione, partenza con ritardo, binario. Risponde con l'ultimo dato
 * salvato e non scade mai: lo si ridisegna quando arriva un dato nuovo, e finché c'è
 * [RefreshWorker] aggiorna il treno attorno alla partenza.
 */
class TrainTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val app = wearApp
            // Tile aggiunti prima che ci fosse l'evento di aggiunta: si segnano alla prima richiesta.
            if (app.surfaces.add(ActiveSurfaces.tile(requestParams.tileId))) RefreshWorker.schedule(
                app
            )
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // 0: nessuna scadenza, l'ora dell'aggiornamento è assoluta e non invecchia.
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(app.refresher.store.load())))
                .build()
        }

    override fun onTileAddEvent(requestParams: EventBuilders.TileAddEvent) {
        val app = wearApp
        if (app.surfaces.add(ActiveSurfaces.tile(requestParams.tileId))) {
            app.scope.launch { RefreshWorker.schedule(app) }
        }
    }

    override fun onTileRemoveEvent(requestParams: EventBuilders.TileRemoveEvent) {
        val app = wearApp
        app.surfaces.remove(ActiveSurfaces.tile(requestParams.tileId))
        app.scope.launch { RefreshWorker.schedule(app) }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    private fun layout(snapshot: WatchSnapshot): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        val selection = snapshot.selection
        if (selection == null) {
            column.addContent(led("NESSUN TRENO", 18f))
                .addContent(spacer())
                .addContent(text("Sceglilo nell'app sul telefono", 12f, LABEL))
        } else {
            val train = snapshot.train
            val live = snapshot.liveTrain()
            column.addContent(label(R.string.label_train))
                .addContent(led(listOfNotNull(train?.category, train?.number ?: selection.trainNumber).joinToString(" "), 14f))
                .addContent(label(R.string.label_destination))
                .addContent(led(train?.destination ?: selection.label.substringAfter("→ ", ""), 18f))
                .addContent(label(R.string.label_departure))
                .addContent(led(listOfNotNull(train?.time ?: "--:--", delayText(live)).joinToString("  "), 18f))
            live?.platform?.takeIf { it.isNotBlank() }?.let {
                column.addContent(label(R.string.label_platform)).addContent(text(it, 16f, LABEL, bold = true))
            }
            column.addContent(spacer()).addContent(text(tileStatusText(snapshot), 10f, STATUS))
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(BLACK)).build())
                    .setClickable(openApp())
                    .build()
            )
            .addContent(column.build())
            .build()
    }

    private fun openApp() = ModifiersBuilders.Clickable.Builder()
        .setId("open")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(TrainActivity::class.java.name)
                        .build()
                )
                .build()
        )
        .build()

    private fun label(@StringRes id: Int) = text(getString(id), 10f, LABEL)

    /** Testo ambra come i LED del tabellone. */
    private fun led(text: String, size: Float) = text(text.uppercase(), size, AMBER, bold = true)

    private fun text(text: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()

    private fun spacer() = LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        const val BLACK = 0xFF000000.toInt()
        const val AMBER = 0xFFFFA51F.toInt()
        const val LABEL = 0xFFF1F1EE.toInt()
        const val STATUS = 0xFFB0B0B0.toInt()
    }
}
