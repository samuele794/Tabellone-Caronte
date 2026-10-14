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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import announce.Announcer
import announce.BoardAnnouncements
import announce.PlaceNames
import cache.StationCache
import cache.StationSource
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
import rfi.RfiMapper
import rfi.Station
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Ogni quanto riscaricare il tabellone (per FNM: i dettagli dei treni). */
private val REFRESH_MILLIS = 30.seconds

/**
 * Stato del tabellone collegato a uno dei [sources] (RFI, FNM…), scelto insieme alla stazione.
 * Con un [announcer] le variazioni tra un aggiornamento e il successivo vengono annunciate a voce;
 * con una [stationCache] gli elenchi stazioni si scaricano al più una volta al mese.
 */
@Stable
class LiveBoardState(
    private val sources: Map<StationSource, BoardSource>,
    initialSource: StationSource = StationSource.Rfi,
    initial: Station = sources.getValue(initialSource).defaultStation,
    private val announcer: Announcer? = null,
    private val stationCache: StationCache? = null,
) {
    private val mapper = RfiMapper()
    private val announcements = BoardAnnouncements()

    /** Fornitori disponibili, nell'ordine delle schede del dialogo stazione. */
    val availableSources: List<StationSource> = StationSource.entries.filter { it in sources }

    private var stationLists by mutableStateOf<Map<StationSource, List<Station>>>(emptyMap())
    private var stationErrors by mutableStateOf<Map<StationSource, String>>(emptyMap())

    /** Elenco stazioni di [source]; vuoto finché non è caricato. */
    fun stations(source: StationSource): List<Station> = stationLists[source].orEmpty()

    fun stationsError(source: StationSource): String? = stationErrors[source]

    var source by mutableStateOf(initialSource)
        private set
    var station by mutableStateOf(initial)
        private set
    var mode by mutableStateOf(BoardMode.Departures)
    var autoScroll by mutableStateOf(true)
    private var announceState by mutableStateOf(true)

    /** Cambia fornitore e stazione insieme. */
    fun select(source: StationSource, station: Station) {
        require(source in sources) { "Fornitore $source non disponibile" }
        this.source = source
        this.station = station
    }

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

    /** Prossimo riscaricamento dell'elenco treni, per i fornitori che lo fanno più di rado. */
    var nextListRefreshAt by mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null)
        private set

    /** Stazione e modalità a cui si riferiscono [departures]. */
    private var loadedKey: Triple<StationSource, String, BoardMode>? = null

    /** Fine dell'ultimo tentativo di aggiornamento, riuscito o no. */
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    /** Quando partirà il prossimo aggiornamento; null finché non è in programma. */
    var nextRefreshAt by mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null)
        private set

    /** Attende [after] prima del prossimo aggiornamento, rendendolo visibile in [nextRefreshAt]. */
    suspend fun waitForRefresh(after: Duration) {
        nextRefreshAt = TimeSource.Monotonic.markNow() + after
        delay(after)
    }

    /**
     * Attesa prima del prossimo aggiornamento: zero se i dati sono di un'altra tabella, altrimenti
     * il resto di [interval]. Serve quando il tabellone torna a schermo (es. dopo una rotazione).
     */
    fun nextRefreshIn(interval: Duration): Duration {
        val last = lastRefresh ?: return Duration.ZERO
        if (loadedKey != Triple(source, station.id, mode)) return Duration.ZERO
        return (interval - last.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    suspend fun loadStations(source: StationSource = this.source) {
        stationErrors -= source
        val client = sources.getValue(source)
        try {
            val list = stationCache?.stations(source, client::stations) ?: client.stations()
            stationLists += source to list
            announcer?.places = PlaceNames(stationLists.values.flatten().map { it.name }.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stationErrors += source to "Elenco stazioni non disponibile: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Scarica il tabellone; se fallisce tiene gli ultimi dati validi della stessa tabella. */
    suspend fun refresh() {
        val key = Triple(source, station.id, mode)
        val client = sources.getValue(key.first)
        if (key != loadedKey) {
            departures = emptyList()
            notices = emptyList()
            nextListRefreshAt = null
            // Gli annunci della stazione precedente non servono più.
            announcer?.clear()
        }
        loading = true
        try {
            val board = client.board(station, key.third)
            departures = mapper.departures(board)
            notices = board.notices
            // Il primo tabellone di una stazione fa solo da base: update() non annuncia nulla.
            val news = announcements.update(key.second, key.third, board)
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
            lastRefresh = TimeSource.Monotonic.markNow()
            nextListRefreshAt = client.nextListRefreshAt
        }
    }
}

/** Tabellone live a tutto schermo; stazione, modalità e scorrimento si cambiano dalla rotella. */
@Composable
fun LiveBoard(state: LiveBoardState, now: LocalDateTime) {
    LaunchedEffect(state.source) {
        if (state.stations(state.source).isEmpty()) state.loadStations()
    }
    LaunchedEffect(state.source, state.station.id, state.mode) {
        state.waitForRefresh(state.nextRefreshIn(REFRESH_MILLIS))
        while (isActive) {
            state.refresh()
            state.waitForRefresh(REFRESH_MILLIS)
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val colors = remember { BoardColors() }
    Box(Modifier.fillMaxSize().background(colors.frame).safeDrawingPadding().keepScreenOn()) {
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
            status = statusLabel(
                state,
                secondsLeft = secondsToRefresh(state.nextRefreshAt),
                listSecondsLeft = secondsToRefresh(state.nextListRefreshAt),
            ),
            onSettingsClick = { showSettings = true },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showSettings) SettingsDialog(state, onDismiss = { showSettings = false })
}

/**
 * Scritta accanto alla rotella: ora dell'ultimo aggiornamento, o perché manca, e quanto manca
 * al prossimo ([secondsLeft]). Per i fornitori che riscaricano l'elenco treni più di rado
 * ([listSecondsLeft]) mostra i due conti alla rovescia: dettagli ed elenco.
 */
internal fun statusLabel(
    state: LiveBoardState,
    secondsLeft: Long?,
    listSecondsLeft: Long?
): String? {
    val updated = state.updatedAt?.format(TimeWithSeconds)
    val last = when {
        state.error != null && updated != null -> "Aggiornamento non riuscito · dati delle $updated"
        state.error != null -> "Aggiornamento non riuscito"
        updated != null -> "Aggiornato alle $updated"
        state.loading -> return "Aggiornamento…"
        else -> return null
    }
    val list = listSecondsLeft?.let { " · elenco tra ${countdown(it)}" }.orEmpty()
    val next = when {
        state.loading -> "aggiornamento…"
        secondsLeft == null -> return last + list
        state.error != null -> "nuovo tentativo tra $secondsLeft s"
        listSecondsLeft != null -> "dettagli tra $secondsLeft s"
        else -> "prossimo tra $secondsLeft s"
    }
    return "$last · $next$list"
}

/** "45 s", "4:05". */
private fun countdown(seconds: Long): String =
    if (seconds < 60) "$seconds s" else "${seconds / 60}:${
        (seconds % 60).toString().padStart(2, '0')
    }"

/** Secondi interi (per eccesso) che mancano a [target], aggiornati allo scatto di ogni secondo. */
@Composable
private fun secondsToRefresh(target: TimeSource.Monotonic.ValueTimeMark?): Long? {
    val seconds by produceState<Long?>(null, target) {
        if (target == null) return@produceState
        while (true) {
            val left = -target.elapsedNow()
            val s = ceil(left.toDouble(DurationUnit.SECONDS)).toLong().coerceAtLeast(0)
            value = s
            if (s == 0L) break
            delay(left - (s - 1).seconds)
        }
    }
    return seconds
}

@Composable
private fun SettingsDialog(state: LiveBoardState, onDismiss: () -> Unit) {
    var pickStation by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impostazioni") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                StationField(state.source, state.station, onClick = { pickStation = true })
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
            onSelect = { source, station ->
                state.select(source, station)
                pickStation = false
            },
            onDismiss = { pickStation = false },
        )
    }
}

/** Campo di sola lettura: un clic apre la scelta della stazione. */
@Composable
private fun StationField(source: StationSource, station: Station, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = station.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Stazione (${source.label})") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Sopra il campo, così il clic non lo mette a fuoco e arriva sempre a onClick.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

/**
 * Elenco delle stazioni, una scheda per fornitore (RFI ~2400 località, FNM ~560), filtrabile dalla
 * barra di ricerca. Scegliere una stazione di un'altra scheda cambia anche il fornitore.
 */
@Composable
private fun StationDialog(
    state: LiveBoardState,
    onSelect: (StationSource, Station) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(state.source) }
    LaunchedEffect(tab) { if (state.stations(tab).isEmpty()) state.loadStations(tab) }
    var query by rememberSaveable { mutableStateOf("") }
    val stations = state.stations(tab)
    val stationsError = state.stationsError(tab)
    val current = state.station.takeIf { tab == state.source }
    val matches = remember(stations, query) {
        val q = query.trim()
        if (q.isEmpty()) stations else stations.filter { it.name.contains(q, ignoreCase = true) }
    }
    val list = rememberLazyListState()
    // Senza filtro si parte dalla stazione attuale; filtrando, dal primo risultato.
    LaunchedEffect(matches) {
        val i = current?.let(matches::indexOf) ?: -1
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
                if (state.availableSources.size > 1) {
                    PrimaryTabRow(
                        selectedTabIndex = state.availableSources.indexOf(tab).coerceAtLeast(0),
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        state.availableSources.forEach { source ->
                            Tab(
                                selected = source == tab,
                                onClick = { tab = source },
                                text = { Text(source.label) },
                            )
                        }
                    }
                }
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
                                    selected = station == current,
                                    onClick = { onSelect(tab, station) })
                            }
                        }

                        stationsError != null -> Text(
                            stationsError,
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
                    if (stationsError != null) {
                        TextButton(onClick = { scope.launch { state.loadStations(tab) } }) { Text("Riprova") }
                    }
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                }
            }
        }
    }
}

/** Nome del fornitore nelle schede e nelle etichette. */
private val StationSource.label
    get() = when (this) {
        StationSource.Rfi -> "RFI"
        StationSource.Fnm -> "FNM"
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
