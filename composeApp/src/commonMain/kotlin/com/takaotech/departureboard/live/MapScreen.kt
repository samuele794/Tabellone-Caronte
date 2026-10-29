package com.takaotech.departureboard.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.takaotech.departureboard.MapFocus
import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.StationMap
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.map_back_to_board
import com.takaotech.departureboard.resources.map_search_clear
import com.takaotech.departureboard.resources.map_search_no_results
import com.takaotech.departureboard.resources.map_search_placeholder
import org.jetbrains.compose.resources.stringResource

/** Suggerimenti al massimo sotto la barra di ricerca. */
private const val MAX_SUGGESTIONS = 8

/**
 * La mappa delle stazioni a tutto schermo, col bottone per tornare al tabellone e la ricerca delle
 * stazioni. Finché è aperta il tabellone non si aggiorna e i suoi dati sono buttati: vedi
 * [LiveBoardState.openMap].
 */
@Composable
internal fun MapScreen(state: LiveBoardState, onBack: () -> Unit) {
    DisposableEffect(state) {
        state.openMap()
        onDispose { state.closeMap() }
    }
    // Tutte le stazioni in cache, anche dei fornitori che il tabellone non ha ancora caricato.
    LaunchedEffect(state) { state.loadMapStations() }
    var tooltip by remember { mutableStateOf<MapStation?>(null) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1E24))) {
        StationMap(
            state.mapStations,
            state.stationPosition,
            tooltip,
            onTooltipChange = { tooltip = it },
            focus,
            Modifier.fillMaxSize(),
        )
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FilledTonalButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(ArrowBack, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.map_back_to_board))
            }
            Spacer(Modifier.width(12.dp))
            StationSearch(
                state.mapStations,
                onPick = { station ->
                    tooltip = station
                    focus = MapFocus(station)
                },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Barra di ricerca con autocompletamento sulle [stations] della mappa: scrivendo compaiono i
 * suggerimenti, toccandone uno (o con Invio, il primo) si chiama [onPick] e la ricerca si svuota.
 * I suggerimenti restano finché c'è testo, anche senza focus: sul desktop il campo lo perde già
 * alla pressione sul suggerimento, e se sparissero lì il clic non arriverebbe mai.
 */
@Composable
private fun StationSearch(
    stations: List<MapStation>,
    onPick: (MapStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val matches = remember(stations, query) { stations.searchStations(query) }
    val pick = { station: MapStation ->
        query = ""
        focusManager.clearFocus()
        onPick(station)
    }
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, shadowElevation = 4.dp) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.map_search_placeholder)) },
                leadingIcon = { Icon(Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Close, stringResource(Res.string.map_search_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { matches.firstOrNull()?.let(pick) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isNotBlank()) {
                HorizontalDivider()
                if (matches.isEmpty()) {
                    Text(
                        stringResource(Res.string.map_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                matches.forEach { station ->
                    Column(
                        Modifier.fillMaxWidth().clickable { pick(station) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(station.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            station.sources.joinToString(" · ") { it.label },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stazioni il cui nome contiene [query], con lo stesso confronto della ricerca nelle impostazioni
 * ([SearchText]): prima quelle che iniziano così, poi le altre, ognuna in ordine alfabetico, al
 * massimo [limit]. Vuoto senza testo.
 */
internal fun List<MapStation>.searchStations(
    query: String,
    limit: Int = MAX_SUGGESTIONS
): List<MapStation> {
    val search = SearchText(query)
    if (search.isEmpty) return emptyList()
    val (starting, containing) = filter { search.matches(it.name) }
        .partition { search.isPrefixOf(it.name) }
    return (starting.sortedBy { it.name } + containing.sortedBy { it.name }).take(limit)
}
