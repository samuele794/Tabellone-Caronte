package com.takaotech.departureboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.Station

/** La piattaforma sa mostrare [StationMap]: altrimenti il tabellone non offre la mappa. */
expect val StationMapSupported: Boolean

/**
 * Un segnaposto sulla mappa: una stazione, con la sua voce negli elenchi di ogni fornitore che
 * la ha, nell'ordine dei fornitori.
 */
@Immutable
data class MapStation(
    val name: String,
    val position: LatLng,
    val stations: Map<StationSource, Station>,
) {
    val sources: List<StationSource> get() = stations.keys.toList()
}

/** Inquadratura della mappa, da ritrovare dopo una rotazione. */
@Immutable
data class MapCamera(val center: LatLng, val zoom: Double, val bearing: Double, val tilt: Double)

/**
 * Richiesta di portare la mappa su [station]. Ogni richiesta è un oggetto nuovo, così scegliere di
 * nuovo la stessa stazione ci riporta la mappa anche dopo averla spostata a mano.
 */
class MapFocus(val station: MapStation)

/**
 * Mappa con tutte le [stations], tutte con lo stesso segnaposto. All'apertura riprende [camera]
 * se c'è (es. dopo una rotazione), altrimenti si centra sulla stazione del tabellone in
 * [selected]; senza nessuno dei due mostra l'Italia. Ogni spostamento passa da [onCameraChange].
 * Toccare una stazione chiama [onStationClick]. A ogni [focus] nuovo la mappa vola sulla sua
 * stazione.
 */
@Composable
expect fun StationMap(
    stations: List<MapStation>,
    selected: LatLng?,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onStationClick: (MapStation) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
)
