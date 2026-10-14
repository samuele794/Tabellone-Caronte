package live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import announce.Announcer
import announce.BoardAnnouncements
import announce.PlaceNames
import debuglog.DebugLog
import departureboard.BoardColors
import departureboard.BoardFooter
import departureboard.BoardLabels
import departureboard.BoardLayout
import departureboard.Departure
import departureboard.DepartureBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import rfi.BoardMode
import rfi.RfiClient
import rfi.RfiMapper
import rfi.Station
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Ogni quanto riscaricare il monitor RFI. */
private val REFRESH_MILLIS = 30.seconds

val MilanoCentrale = Station(1728, "MILANO CENTRALE")

/**
 * Stato del tabellone collegato al monitor RFI. Con un [announcer] le variazioni tra un
 * aggiornamento e il successivo vengono annunciate a voce.
 */
@Stable
class LiveBoardState(
    private val client: RfiClient,
    initial: Station = MilanoCentrale,
    private val announcer: Announcer? = null,
) {
    private val mapper = RfiMapper()
    private val announcements = BoardAnnouncements()

    var stations by mutableStateOf<List<Station>>(emptyList())
        private set
    var stationsError by mutableStateOf<String?>(null)
        private set
    var station by mutableStateOf(initial)
    var mode by mutableStateOf(BoardMode.Departures)
    var autoScroll by mutableStateOf(true)
    private var announceState by mutableStateOf(true)

    /** Annunci sonori attivi; spegnendoli si interrompe anche quello in corso. */
    var announce: Boolean
        get() = announceState
        set(value) {
            announceState = value
            if (!value) announcer?.clear()
        }

    /** Se il tabellone ha un motore per gli annunci sonori. */
    val canAnnounce get() = announcer != null

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

    /** Ultimo tentativo di aggiornamento, riuscito o no. */
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Attesa prima del prossimo aggiornamento: zero se i dati sono di un'altra tabella, altrimenti
     * il resto di [interval]. Serve quando il tabellone torna a schermo (es. dopo una rotazione).
     */
    fun nextRefreshIn(interval: Duration): Duration {
        val last = lastRefresh ?: return Duration.ZERO
        if (loadedKey != (station.id to mode)) return Duration.ZERO
        return (interval - last.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    suspend fun loadStations() {
        stationsError = null
        try {
            stations = client.stations()
            announcer?.places = PlaceNames(stations.map { it.name })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stationsError = "Elenco stazioni non disponibile: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Scarica il monitor; se fallisce tiene gli ultimi dati validi della stessa tabella. */
    suspend fun refresh() {
        val key = station.id to mode
        if (key != loadedKey) {
            departures = emptyList()
            notices = emptyList()
            // Gli annunci della stazione precedente non servono più.
            announcer?.clear()
        }
        loading = true
        lastRefresh = TimeSource.Monotonic.markNow()
        try {
            val board = client.board(key.first, key.second)
            departures = mapper.departures(board)
            notices = board.notices
            // Il primo tabellone di una stazione fa solo da base: update() non annuncia nulla.
            val news = announcements.update(key.first, key.second, board)
            when {
                news.isEmpty() -> Unit
                announce -> announcer?.enqueue(news)
                else -> DebugLog.d(Announcer.TAG) { "Annunci spenti: scartati ${news.joinToString { it.code }}" }
            }
            loadedKey = key
            updatedAt = localNow()
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

/** Tabellone live a tutto schermo; stazione, modalità e scorrimento si cambiano dalla rotella. */
@Composable
fun LiveBoard(state: LiveBoardState, now: LocalDateTime) {
    LaunchedEffect(Unit) { if (state.stations.isEmpty()) state.loadStations() }
    LaunchedEffect(state.station.id, state.mode) {
        delay(state.nextRefreshIn(REFRESH_MILLIS))
        while (isActive) {
            state.refresh()
            delay(REFRESH_MILLIS)
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val colors = remember { BoardColors() }
    Box(Modifier.fillMaxSize().background(colors.frame).safeDrawingPadding()) {
        DepartureBoard(
            departures = state.departures,
            footer = boardFooter(now, state.notices, state.station.name),
            labels = if (state.mode == BoardMode.Arrivals) BoardLabels.Arrivals else BoardLabels(),
            layout = BoardLayout(
                rows = null,
                autoScroll = state.autoScroll,
                logoCols = RfiMapper.LOGO_COLS,
                logoDensity = RfiMapper.LOGO_DENSITY,
            ),
            colors = colors,
            status = statusLabel(state),
            onSettingsClick = { showSettings = true },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showSettings) SettingsDialog(state, onDismiss = { showSettings = false })
}

/** Scritta accanto alla rotella: ora dell'ultimo aggiornamento, o perché manca. */
private fun statusLabel(state: LiveBoardState): String? {
    val updated = state.updatedAt?.format(TimeWithSeconds)
    return when {
        state.error != null && updated != null -> "Aggiornamento non riuscito · dati delle $updated"
        state.error != null -> "Aggiornamento non riuscito"
        updated != null -> "Aggiornato alle $updated"
        state.loading -> "Aggiornamento…"
        else -> null
    }
}

@Composable
private fun SettingsDialog(state: LiveBoardState, onDismiss: () -> Unit) {
    var pickStation by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impostazioni") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                StationField(state.station, onClick = { pickStation = true })
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BoardMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { state.mode = mode },
                            shape = SegmentedButtonDefaults.itemShape(i, BoardMode.entries.size),
                        ) { Text(if (mode == BoardMode.Departures) "Partenze" else "Arrivi") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Scorrimento automatico", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = state.autoScroll, onCheckedChange = { state.autoScroll = it })
                }
                if (state.canAnnounce) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Annunci sonori", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = state.announce, onCheckedChange = { state.announce = it })
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )

    if (pickStation) {
        StationDialog(
            state,
            onSelect = {
                state.station = it
                pickStation = false
            },
            onDismiss = { pickStation = false },
        )
    }
}

/** Campo di sola lettura: un clic apre la scelta della stazione. */
@Composable
private fun StationField(station: Station, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = station.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Stazione") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Sopra il campo, così il clic non lo mette a fuoco e arriva sempre a onClick.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

/** Elenco completo delle ~2400 località RFI, filtrabile dalla barra di ricerca. */
@Composable
private fun StationDialog(
    state: LiveBoardState,
    onSelect: (Station) -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) { if (state.stations.isEmpty()) state.loadStations() }
    var query by rememberSaveable { mutableStateOf("") }
    val stations = state.stations
    val matches = remember(stations, query) {
        val q = query.trim()
        if (q.isEmpty()) stations else stations.filter { it.name.contains(q, ignoreCase = true) }
    }
    val list = rememberLazyListState()
    // Senza filtro si parte dalla stazione attuale; filtrando, dal primo risultato.
    LaunchedEffect(matches) {
        val i = matches.indexOf(state.station)
        list.scrollToItem(if (query.isBlank() && i > 0) i - 1 else 0)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.padding(24.dp).widthIn(max = 520.dp).heightIn(max = 680.dp).fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                Text(
                    "Stazione",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cerca stazione") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                        .focusRequester(focus),
                )
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        stations.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                            items(matches, key = { it.id }) { station ->
                                StationItem(
                                    station,
                                    selected = station == state.station,
                                    onClick = { onSelect(station) })
                            }
                        }

                        state.stationsError != null -> Text(
                            state.stationsError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )

                        else -> CircularProgressIndicator(Modifier.size(32.dp))
                    }
                    if (stations.isNotEmpty() && matches.isEmpty()) {
                        Text(
                            "Nessuna stazione trovata",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (state.stationsError != null) {
                        TextButton(onClick = { scope.launch { state.loadStations() } }) { Text("Riprova") }
                    }
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                }
            }
        }
    }
}

@Composable
private fun StationItem(station: Station, selected: Boolean, onClick: () -> Unit) {
    Text(
        station.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** Ora locale aggiornata a ogni cambio di minuto (il tabellone mostra solo HH:MM). */
@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(localNow().truncatedToMinute()) {
        while (true) {
            val t = localNow()
            value = t.truncatedToMinute()
            delay(60_000L - (t.second * 1_000L + t.nanosecond / 1_000_000))
        }
    }
    return now
}

private fun localNow(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

private fun LocalDateTime.truncatedToMinute() = LocalDateTime(date, LocalTime(hour, minute))

private val TimeWithSeconds = LocalDateTime.Format {
    hour(); char(':'); minute(); char(':'); second()
}

private val ClockFormat = LocalDateTime.Format { hour(); char(':'); minute() }

/** Abbreviazioni italiane a 3 lettere, come `EEE`/`MMM` di `Locale.ITALIAN`. */
private val DayNames = DayOfWeekNames("lun", "mar", "mer", "gio", "ven", "sab", "dom")
private val MonthAbbreviations = MonthNames(
    "gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic",
)

/**
 * Fascia inferiore con orologio e data locali: gli avvisi scorrono nella prima riga, la seconda
 * mostra la stazione del tabellone.
 */
fun boardFooter(now: LocalDateTime, notices: List<String>, station: String): BoardFooter =
    BoardFooter(
        clock = now.format(ClockFormat),
        date = listOf(
            DayNames.names[now.dayOfWeek.ordinal],
            now.day.toString(),
            MonthAbbreviations.names[now.month.ordinal],
        ),
        messages = listOf(notices.joinToString("   "), station),
    )
