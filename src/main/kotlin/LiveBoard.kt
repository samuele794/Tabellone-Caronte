import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import departureboard.BoardFooter
import departureboard.BoardLabels
import departureboard.BoardLayout
import departureboard.Departure
import departureboard.DepartureBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import rfi.BoardMode
import rfi.RfiClient
import rfi.RfiMapper
import rfi.Station
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** Ogni quanto riscaricare il monitor RFI. */
private val REFRESH_MILLIS = 30.seconds

val MilanoCentrale = Station(1728, "MILANO CENTRALE")

/** Stato del tabellone collegato al monitor RFI. */
@Stable
class LiveBoardState(private val client: RfiClient, initial: Station = MilanoCentrale) {
    private val mapper = RfiMapper()

    var stations by mutableStateOf<List<Station>>(emptyList())
        private set
    var station by mutableStateOf(initial)
    var mode by mutableStateOf(BoardMode.Departures)
    var autoScroll by mutableStateOf(true)

    var departures by mutableStateOf<List<Departure>>(emptyList())
        private set
    var notices by mutableStateOf<List<String>>(emptyList())
        private set
    var updatedAt by mutableStateOf<LocalDateTime?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Stazione e modalità a cui si riferiscono [departures]. */
    private var loadedKey: Pair<Int, BoardMode>? = null

    suspend fun loadStations() {
        try {
            stations = client.stations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Elenco stazioni non disponibile: ${e.message}"
        }
    }

    /** Scarica il monitor; se fallisce tiene gli ultimi dati validi della stessa tabella. */
    suspend fun refresh() {
        val key = station.id to mode
        if (key != loadedKey) {
            departures = emptyList()
            notices = emptyList()
        }
        loading = true
        try {
            val board = client.board(key.first, key.second)
            departures = mapper.departures(board)
            notices = board.notices
            loadedKey = key
            updatedAt = LocalDateTime.now()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Aggiornamento non riuscito: ${e.message ?: e::class.simpleName}"
        } finally {
            loading = false
        }
    }
}

@Composable
fun LiveBoard(state: LiveBoardState, now: LocalDateTime) {
    LaunchedEffect(Unit) { state.loadStations() }
    LaunchedEffect(state.station.id, state.mode) {
        while (isActive) {
            state.refresh()
            delay(REFRESH_MILLIS)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFD9D9D6)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar(state)
        DepartureBoard(
            departures = state.departures,
            footer = boardFooter(now, state.notices),
            labels = if (state.mode == BoardMode.Arrivals) BoardLabels.Arrivals else BoardLabels(),
            layout = BoardLayout(
                rows = null,
                autoScroll = state.autoScroll,
                logoCols = RfiMapper.LOGO_COLS,
                logoDensity = RfiMapper.LOGO_DENSITY,
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun TopBar(state: LiveBoardState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationPicker(state.stations, state.station, onSelect = { state.station = it })
        SingleChoiceSegmentedButtonRow {
            BoardMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { state.mode = mode },
                    shape = SegmentedButtonDefaults.itemShape(i, BoardMode.entries.size),
                ) { Text(if (mode == BoardMode.Departures) "Partenze" else "Arrivi") }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = state.autoScroll, onCheckedChange = { state.autoScroll = it })
            Text("Scorrimento automatico", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            state.updatedAt?.let {
                Text(
                    "Aggiornato alle ${it.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Campo di ricerca con tendina sulle ~2400 località RFI. */
@Composable
private fun StationPicker(stations: List<Station>, selected: Station, onSelect: (Station) -> Unit) {
    var query by remember(selected) { mutableStateOf(selected.name) }
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(stations, query) {
        val q = query.trim()
        if (q.isEmpty() || q == selected.name) stations.take(50)
        else stations.filter { it.name.contains(q, ignoreCase = true) }.take(50)
    }

    Box {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text("Stazione") },
            singleLine = true,
            enabled = stations.isNotEmpty(),
            modifier = Modifier.width(360.dp),
        )
        DropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = {
                expanded = false
                query = selected.name
            },
            // Non ruba il focus: si continua a scrivere mentre la lista si aggiorna.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.width(360.dp).heightIn(max = 400.dp),
        ) {
            matches.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = {
                        expanded = false
                        query = station.name
                        onSelect(station)
                    },
                )
            }
        }
    }
}

/** Fascia inferiore con orologio e data locali. */
fun boardFooter(now: LocalDateTime, messages: List<String>): BoardFooter {
    val it = Locale.ITALIAN
    return BoardFooter(
        clock = now.format(DateTimeFormatter.ofPattern("HH:mm")),
        date = listOf(
            now.format(DateTimeFormatter.ofPattern("EEE", it)).take(3),
            now.dayOfMonth.toString(),
            now.format(DateTimeFormatter.ofPattern("MMM", it)).take(3),
        ),
        messages = messages,
    )
}
