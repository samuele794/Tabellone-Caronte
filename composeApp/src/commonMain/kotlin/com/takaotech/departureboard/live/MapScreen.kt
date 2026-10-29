package com.takaotech.departureboard.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import com.takaotech.departureboard.MapFocus
import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.StationMap
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.map_back_to_board
import com.takaotech.departureboard.resources.map_search_clear
import com.takaotech.departureboard.resources.map_search_no_results
import com.takaotech.departureboard.resources.map_search_placeholder
import com.takaotech.departureboard.resources.map_windows_limit
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Suggerimenti al massimo sotto la barra di ricerca. */
private const val MAX_SUGGESTIONS = 8

/** Larghezza di una finestra, se lo schermo la consente. */
private val MAP_BOARD_WIDTH = 560.dp

/** Larghezza minima a cui si stringe una finestra dai bordi. */
private val MIN_WINDOW_WIDTH = 400.dp

/** Parte di una finestra che resta sempre nella schermata: la barra del titolo, per riprenderla. */
private val WINDOW_GRIP = 56.dp

/** Margine attorno al bottone indietro e alla ricerca, che le finestre lasciano libero. */
private val BAR_PADDING = 16.dp

/** Bottone per tornare al tabellone, abbassato per centrarsi sul campo di ricerca. */
private val BACK_SIZE = 48.dp
private val BACK_TOP = 4.dp

/** Spazio tra il bottone indietro e la ricerca. */
private val SEARCH_GAP = 12.dp

/**
 * La mappa delle stazioni a tutto schermo, col bottone per tornare al tabellone ([onBack]) e la
 * ricerca delle stazioni. Finché è aperta il tabellone non si aggiorna e i suoi dati sono buttati:
 * vedi [LiveBoardState.openMap]. Toccando una stazione, o scegliendola dalla ricerca, si apre una
 * finestra flottante col suo tabellone ([MapBoardWindow]), al più
 * [LiveBoardState.MAX_MAP_BOARDS] insieme (oltre, una snackbar chiede di chiuderne una): si
 * aprono a cascata, si trascinano dalla barra del titolo e restano ferme mentre la mappa si muove;
 * toccarne una la porta sopra le altre, il suo bottone apre il tabellone completo
 * ([onOpenBoard]). Sul desktop si allargano dai bordi, su mobile si portano a tutto schermo
 * ([ResizableMapWindows]). Finestre, inquadratura e ricerca restano dopo una rotazione.
 */
@Composable
internal fun MapScreen(state: LiveBoardState, onBack: () -> Unit, onOpenBoard: (MapBoard) -> Unit) {
    // Tutte le stazioni in cache, anche dei fornitori che il tabellone non ha ancora caricato.
    LaunchedEffect(state) { state.loadMapStations() }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val limitMessage = stringResource(Res.string.map_windows_limit, LiveBoardState.MAX_MAP_BOARDS)
    // Misure del campo di ricerca, che cresce coi caratteri grandi; i suggerimenti non contano.
    var searchSize by remember { mutableStateOf(DpSize(0.dp, 56.dp)) }
    // Zona di bottone indietro e ricerca, col loro margine: le finestre ci stanno fuori.
    val keepOut = DpRect(
        left = 0.dp,
        top = 0.dp,
        right = BAR_PADDING + BACK_SIZE + SEARCH_GAP + searchSize.width + BAR_PADDING,
        bottom = BAR_PADDING * 2 + max(searchSize.height, BACK_TOP + BACK_SIZE),
    )
    val windowSizes = remember { mutableStateMapOf<MapBoard, DpSize>() }

    // Con le finestre al massimo si avvisa, una volta sola anche toccando più stazioni mentre
    // l'avviso è a schermo.
    fun open(station: MapStation) {
        if (!state.openMapBoard(station) && snackbar.currentSnackbarData == null) {
            scope.launch { snackbar.showSnackbar(limitMessage) }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1E24))) {
        StationMap(
            state.mapStations,
            state.stationPosition,
            camera = state.mapCamera,
            onCameraChange = { state.mapCamera = it },
            onStationClick = ::open,
            focus,
            Modifier.fillMaxSize(),
        )
        // Le finestre sopra la mappa, l'ultima aperta o toccata in cima. Non coprono bottone
        // indietro e ricerca ([keepOut]); i suggerimenti, disegnati dopo, passano sopra di loro.
        // Una finestra a tutto schermo copre invece ogni cosa, da bordo a bordo: è lei a tenere
        // il contenuto fuori da barre e notch, e intanto la schermata resta in orizzontale.
        val fullScreen = state.mapBoards.any { it.fullScreen }
        if (fullScreen) LandscapeLock()
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .then(if (fullScreen) Modifier.zIndex(1f) else Modifier.safeDrawingPadding())
        ) {
            val defaultWidth = min(MAP_BOARD_WIDTH, maxWidth - 32.dp)
            val area = DpSize(maxWidth, maxHeight)

            // Larghezza scelta dai bordi, se c'è, ma mai oltre la schermata (es. dopo una rotazione).
            fun MapBoard.windowWidth() = (width ?: defaultWidth).coerceAtMost(maxWidth)

            // Dove sta davvero la finestra: anche dopo una rotazione, dentro la schermata e fuori
            // da bottone e ricerca. Finché non è misurata conta la sola barra del titolo.
            fun DpOffset.placed(board: MapBoard) = placeWindow(
                this,
                windowSizes[board]?.copy(width = board.windowWidth())
                    ?: DpSize(board.windowWidth(), WINDOW_GRIP),
                area,
                keepOut,
            )

            fun Offset.toDp() = with(density) { DpOffset(x.toDp(), y.toDp()) }
            state.mapBoards.forEach { board ->
                key(board) {
                    MapBoardWindow(
                        state,
                        board,
                        onClose = {
                            state.closeMapBoard(board)
                            windowSizes -= board
                        },
                        onDrag = { drag ->
                            if (!board.fullScreen) {
                                board.offset =
                                    (board.offset.placed(board) + drag.toDp()).placed(board)
                            }
                        },
                        onOpenBoard = { onOpenBoard(board) },
                        onResize = if (ResizableMapWindows) { edge, drag ->
                            val size =
                                windowSizes[board] ?: DpSize(board.windowWidth(), WINDOW_GRIP)
                            val (offset, width) = resizeWindow(
                                edge,
                                drag.toDp(),
                                board.offset.placed(board),
                                size.copy(width = board.windowWidth()),
                                area,
                            )
                            board.width = width
                            board.offset = offset
                        } else null,
                        onToggleFullScreen = if (ResizableMapWindows) null else {
                            { board.fullScreen = !board.fullScreen }
                        },
                        modifier = if (board.fullScreen) {
                            Modifier.fillMaxSize()
                        } else {
                            // Misurata solo fuori dal tutto schermo: è la misura a cui tornare.
                            Modifier
                                .offset {
                                    board.offset.placed(board)
                                        .let { IntOffset(it.x.roundToPx(), it.y.roundToPx()) }
                                }
                                .width(board.windowWidth())
                                .onSizeChanged {
                                    windowSizes[board] =
                                        with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
                                }
                        }.onPress { open(board.station) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(BAR_PADDING),
            verticalAlignment = Alignment.Top,
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.padding(top = BACK_TOP).size(BACK_SIZE)
            ) {
                Icon(ArrowBack, stringResource(Res.string.map_back_to_board))
            }
            Spacer(Modifier.width(SEARCH_GAP))
            StationSearch(
                state.mapStations,
                onPick = { station ->
                    open(station)
                    focus = MapFocus(station)
                },
                onFieldSize = { searchSize = it },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 420.dp),
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).zIndex(2f).safeDrawingPadding()
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * Dove mettere una finestra di [size] chiesta in [offset], nell'[area] delle finestre: dentro,
 * almeno con la barra del titolo ([WINDOW_GRIP]), e fuori da [keepOut]. Se ci cade sopra si sposta
 * del meno possibile, sotto o a destra: trascinandola, ci scivola attorno.
 */
internal fun placeWindow(offset: DpOffset, size: DpSize, area: DpSize, keepOut: DpRect): DpOffset {
    val maxX = (area.width - size.width).coerceAtLeast(0.dp)
    val maxY = (area.height - WINDOW_GRIP).coerceAtLeast(0.dp)
    fun DpOffset.inside() = DpOffset(x.coerceIn(0.dp, maxX), y.coerceIn(0.dp, maxY))
    fun DpOffset.overlaps() = x < keepOut.right && x + size.width > keepOut.left &&
            y < keepOut.bottom && y + size.height > keepOut.top

    val inside = offset.inside()
    if (!inside.overlaps()) return inside
    val below = DpOffset(inside.x, keepOut.bottom).inside()
    val beside = DpOffset(keepOut.right, inside.y).inside()
    return listOf(below, beside).filterNot { it.overlaps() }
        .minByOrNull { (it.x - inside.x) + (it.y - inside.y) } ?: below
}

/**
 * Finestra di [size] in [offset] ridimensionata trascinando [edge] di [drag]: la sua nuova
 * posizione e larghezza, tra [MIN_WINDOW_WIDTH] e lo spazio che resta nell'[area] da quel lato.
 * Il tabellone tiene le proporzioni, quindi l'altezza segue la larghezza: dal bordo in basso la
 * finestra si allarga verso destra, da un angolo conta lo spostamento più ampio dei due. Dai bordi
 * di sinistra resta fermo il lato destro.
 */
internal fun resizeWindow(
    edge: WindowEdge,
    drag: DpOffset,
    offset: DpOffset,
    size: DpSize,
    area: DpSize,
): Pair<DpOffset, Dp> {
    val sideways = if (edge.left) -drag.x else drag.x
    val down = if (size.height > 0.dp) drag.y * (size.width / size.height) else 0.dp
    val grow = when (edge) {
        WindowEdge.Left, WindowEdge.Right -> sideways
        WindowEdge.Bottom -> down
        WindowEdge.BottomLeft, WindowEdge.BottomRight ->
            if (abs(sideways.value) >= abs(down.value)) sideways else down
    }
    val room = if (edge.left) offset.x + size.width else area.width - offset.x
    val width = (size.width + grow).coerceIn(min(MIN_WINDOW_WIDTH, room), room)
    val x = if (edge.left) offset.x + size.width - width else offset.x
    return DpOffset(x, offset.y) to width
}

/** Chiama [onPress] a ogni pressione, senza togliere il tocco a quello che c'è dentro. */
private fun Modifier.onPress(onPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPress()
    }
}

/**
 * Barra di ricerca con autocompletamento sulle [stations] della mappa: scrivendo compaiono i
 * suggerimenti, toccandone uno (o con Invio, il primo) si chiama [onPick] e la ricerca si svuota.
 * [onFieldSize] riceve le misure del solo campo, senza i suggerimenti.
 * I suggerimenti restano finché c'è testo, anche senza focus: sul desktop il campo lo perde già
 * alla pressione sul suggerimento, e se sparissero lì il clic non arriverebbe mai.
 */
@Composable
private fun StationSearch(
    stations: List<MapStation>,
    onPick: (MapStation) -> Unit,
    onFieldSize: (DpSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var query by rememberSaveable { mutableStateOf("") }
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
                modifier = Modifier.fillMaxWidth()
                    .onSizeChanged {
                        onFieldSize(with(density) {
                            DpSize(
                                it.width.toDp(),
                                it.height.toDp()
                            )
                        })
                    },
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
