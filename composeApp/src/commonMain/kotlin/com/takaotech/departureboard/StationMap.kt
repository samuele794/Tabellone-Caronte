package com.takaotech.departureboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.LatLng

/** La piattaforma sa mostrare [StationMap]: altrimenti il tabellone non offre la mappa. */
expect val StationMapSupported: Boolean

/** Un segnaposto sulla mappa: una stazione, presente negli elenchi di [sources]. */
@Immutable
data class MapStation(val name: String, val position: LatLng, val sources: List<StationSource>)

/**
 * Richiesta di portare la mappa su [station]. Ogni richiesta è un oggetto nuovo, così scegliere di
 * nuovo la stessa stazione ci riporta la mappa anche dopo averla spostata a mano.
 */
class MapFocus(val station: MapStation)

/**
 * Mappa con tutte le [stations], tutte con lo stesso segnaposto, centrata all'apertura su quella
 * del tabellone in [selected]; senza [selected] mostra l'Italia. Su [tooltip] è aperta la scheda
 * della stazione: toccarne un'altra, o chiuderla, passa da [onTooltipChange]. A ogni [focus]
 * nuovo la mappa vola sulla sua stazione.
 */
@Composable
expect fun StationMap(
    stations: List<MapStation>,
    selected: LatLng?,
    tooltip: MapStation?,
    onTooltipChange: (MapStation?) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
)
