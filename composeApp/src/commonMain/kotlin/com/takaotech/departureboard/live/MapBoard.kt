package com.takaotech.departureboard.live

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.map_open_board
import com.takaotech.departureboard.resources.map_tooltip_close
import com.takaotech.departureboard.resources.map_window_fullscreen
import com.takaotech.departureboard.resources.map_window_restore
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Station
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/** Righe del tabellone di una finestra sulla mappa. */
private const val MAP_BOARD_ROWS = 5

/** Dove si apre la prima finestra: sotto il bottone del tabellone e la ricerca. */
private val FIRST_WINDOW = DpOffset(16.dp, 88.dp)

/** Spostamento di ogni finestra rispetto alla precedente, così non si coprono del tutto. */
private val WINDOW_CASCADE = 32.dp

/** Spessore dei bordi da cui si ridimensiona una finestra, e lato degli angoli. */
private val RESIZE_EDGE = 6.dp
private val RESIZE_CORNER = 12.dp

/**
 * Le finestre sulla mappa si usano col puntatore (desktop): si allargano trascinandone i bordi.
 * Altrimenti (mobile) ognuna ha un bottone per portarla a tutto schermo e riportarla com'era.
 */
internal expect val ResizableMapWindows: Boolean

/**
 * Bordo di una finestra da cui la si ridimensiona; [left] se trascinandolo si sposta il lato
 * sinistro. In alto c'è la barra del titolo, che la sposta.
 */
internal enum class WindowEdge(val left: Boolean) {
    Left(true), Right(false), Bottom(false), BottomLeft(true), BottomRight(false)
}

/** Puntatore sopra [WindowEdge]: le frecce del ridimensionamento, dove la piattaforma le ha. */
internal expect fun WindowEdge.pointerIcon(): PointerIcon

/**
 * Finché è nella composizione tiene la schermata in orizzontale (intro, tabellone, una finestra a
 * tutto schermo su mobile); quando non ne resta nessuno la rotazione torna libera. Dove non si
 * ruota non fa nulla.
 */
@Composable
internal expect fun LandscapeLock()

/**
 * Finché è nella composizione mostra le barre di sistema, che altrimenti l'app tiene nascoste (il
 * tabellone è a tutto schermo); uscendo le nasconde di nuovo. Dove non ce ne sono non fa nulla.
 */
@Composable
internal expect fun SystemBarsShown()

/**
 * Finestra di una [station] sopra la mappa: il tabellone di [boardStation] di [source], in
 * partenze o arrivi a scelta ([mode]), nel punto [offset] della schermata; si apre a cascata,
 * spostata di [slot] passi. Si aggiorna come quello a tutto schermo ([BoardFeed]) ma senza
 * annunci: le righe che cambiano lampeggiano subito, per [blinkDuration].
 */
@Stable
class MapBoard internal constructor(
    val station: MapStation,
    val source: StationSource,
    val boardStation: Station,
    sources: Map<StationSource, BoardSource>,
    blinkDuration: () -> Duration,
    slot: Int = 0,
) {
    var mode by mutableStateOf(BoardMode.Departures)

    /**
     * Angolo in alto a sinistra della finestra nell'area della mappa; in dp, così dopo una
     * rotazione resta allo stesso punto.
     */
    var offset by mutableStateOf(
        DpOffset(FIRST_WINDOW.x + WINDOW_CASCADE * slot, FIRST_WINDOW.y + WINDOW_CASCADE * slot)
    )

    /** Larghezza scelta trascinando i bordi (desktop); null finché si usa quella predefinita. */
    var width by mutableStateOf<Dp?>(null)

    /**
     * A tutto schermo (mobile): [offset] e [width] restano quelli di prima, per tornarci.
     */
    var fullScreen by mutableStateOf(false)

    val feed = BoardFeed(sources, { BoardTarget(source, boardStation, mode) }, blinkDuration)
}

/**
 * La finestra flottante di [board]: nella barra del titolo nome della stazione (che scorre se non
 * entra) e X per chiuderla ([onClose]); trascinando la barra si sposta
 * ([onDrag], di quanto si è mosso il puntatore). Sotto c'è il tabellone principale
 * ([LiveDepartureBoard]) con l'aspetto scelto in [state], ma con al più [MAP_BOARD_ROWS] righe e
 * senza orologio, avvisi e bottoni diversi dall'aggiornamento; in basso il selettore
 * partenze/arrivi e, a destra, il bottone che apre il tabellone completo ([onOpenBoard]).
 * Con [onResize] i bordi, tranne quello in alto, ridimensionano la finestra (di quanto si è mosso
 * il puntatore); con [onToggleFullScreen] accanto alla X c'è il bottone del tutto schermo: lì il
 * tabellone occupa lo spazio rimasto, rimpicciolito se serve per entrarci.
 */
@Composable
internal fun MapBoardWindow(
    state: LiveBoardState,
    board: MapBoard,
    onClose: () -> Unit,
    onDrag: (Offset) -> Unit,
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier,
    onResize: ((WindowEdge, Offset) -> Unit)? = null,
    onToggleFullScreen: (() -> Unit)? = null,
) {
    LaunchedEffect(board) { board.feed.runBlinks() }
    AutoRefresh(
        board.feed,
        state.refreshInterval(board.source),
        target = board.mode,
        enabled = true
    )
    val drag by rememberUpdatedState(onDrag)
    Box(modifier) {
        MapBoardContent(state, board, drag, onClose, onOpenBoard, onToggleFullScreen)
        if (onResize != null) ResizeEdges(onResize, Modifier.matchParentSize())
    }
}

@Composable
private fun MapBoardContent(
    state: LiveBoardState,
    board: MapBoard,
    onDrag: (Offset) -> Unit,
    onClose: () -> Unit,
    onOpenBoard: () -> Unit,
    onToggleFullScreen: (() -> Unit)?,
) {
    val full = board.fullScreen
    Surface(
        if (full) Modifier.fillMaxSize() else Modifier,
        shape = if (full) RectangleShape else RoundedCornerShape(12.dp),
        shadowElevation = if (full) 0.dp else 8.dp,
    ) {
        Column(
            // A tutto schermo la finestra va da bordo a bordo: il contenuto resta fuori da barre e notch.
            (if (full) Modifier.safeDrawingPadding() else Modifier).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onDrag(amount)
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                    Text(
                        board.station.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    Text(board.source.label, style = MaterialTheme.typography.bodySmall)
                }
                if (onToggleFullScreen != null) {
                    IconButton(onClick = onToggleFullScreen) {
                        if (full) {
                            Icon(FullscreenExit, stringResource(Res.string.map_window_restore))
                        } else {
                            Icon(Fullscreen, stringResource(Res.string.map_window_fullscreen))
                        }
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Close, stringResource(Res.string.map_tooltip_close))
                }
            }
            LiveDepartureBoard(
                state,
                board.feed,
                board.mode,
                rows = MAP_BOARD_ROWS,
                fitHeight = full,
                modifier = Modifier.fillMaxWidth()
                    .then(if (full) Modifier.weight(1f) else Modifier),
            )
            // Su schermi stretti il bottone va a capo invece di schiacciarsi.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                BoardModeSelector(board.mode, { board.mode = it })
                FilledTonalButton(
                    onClick = onOpenBoard,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(Res.string.map_open_board), maxLines = 1)
                }
            }
        }
    }
}

/**
 * I bordi che ridimensionano una finestra: sinistro, destro e in basso, con i due angoli in basso.
 * Trascinandone uno si chiama [onResize] col bordo e di quanto si è mosso il puntatore.
 */
@Composable
private fun ResizeEdges(onResize: (WindowEdge, Offset) -> Unit, modifier: Modifier) {
    val resize by rememberUpdatedState(onResize)
    Box(modifier) {
        @Composable
        fun Handle(edge: WindowEdge, handle: Modifier) = Box(
            handle.pointerHoverIcon(edge.pointerIcon()).pointerInput(edge) {
                detectDragGestures { change, amount ->
                    change.consume()
                    resize(edge, amount)
                }
            }
        )
        Handle(
            WindowEdge.Left,
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(RESIZE_EDGE)
        )
        Handle(
            WindowEdge.Right,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(RESIZE_EDGE)
        )
        Handle(
            WindowEdge.Bottom,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(RESIZE_EDGE)
        )
        Handle(WindowEdge.BottomLeft, Modifier.align(Alignment.BottomStart).size(RESIZE_CORNER))
        Handle(WindowEdge.BottomRight, Modifier.align(Alignment.BottomEnd).size(RESIZE_CORNER))
    }
}
