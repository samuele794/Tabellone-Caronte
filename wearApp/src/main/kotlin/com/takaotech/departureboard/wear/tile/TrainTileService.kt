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
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.wear.R
import com.takaotech.departureboard.wear.ui.TrainActivity
import com.takaotech.departureboard.wear.ui.delayText
import com.takaotech.departureboard.wear.ui.liveTrain
import com.takaotech.departureboard.wear.ui.compactStatusText
import com.takaotech.departureboard.wear.wearApp
import kotlin.time.Duration.Companion.minutes

/**
 * Tile del treno seguito: destinazione, partenza con ritardo, binario. Risponde subito con
 * l'ultimo dato salvato; se è vecchio chiede un aggiornamento, che arrivando ridisegna il tile.
 */
class TrainTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val app = wearApp
            val snapshot = app.refresher.store.load()
            val now = System.currentTimeMillis()
            app.refresher.refreshIfStale(snapshot)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS.inWholeMilliseconds)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(snapshot, now)))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    private fun layout(snapshot: WatchSnapshot, now: Long): LayoutElementBuilders.LayoutElement {
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
            column.addContent(spacer()).addContent(text(compactStatusText(snapshot, now), 10f, STATUS))
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

        /** Ogni quanto il sistema richiede il tile anche senza dati nuovi. */
        val FRESHNESS = 5.minutes

        const val BLACK = 0xFF000000.toInt()
        const val AMBER = 0xFFFFA51F.toInt()
        const val LABEL = 0xFFF1F1EE.toInt()
        const val STATUS = 0xFFB0B0B0.toInt()
    }
}
