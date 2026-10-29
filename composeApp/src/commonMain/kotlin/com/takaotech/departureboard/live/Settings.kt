package com.takaotech.departureboard.live

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavKey
import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.live.LiveBoardState.Companion.BACKGROUND_VOLUME_OPTIONS
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.board_arrivals
import com.takaotech.departureboard.resources.board_departures
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchedTrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Pagine delle impostazioni: la principale e quelle che si aprono dal suo menu. Sono destinazioni
 * di Navigation 3 sopra il tabellone ([BoardRoute]); una pagina nuova va registrata anche in
 * `RouteSaving` (LiveBoard.kt), per salvare il back stack.
 */
@Serializable
sealed interface SettingsPage : NavKey {
    val title: String

    @Serializable
    data object Main : SettingsPage {
        override val title get() = "Impostazioni"
    }

    @Serializable
    data object Audio : SettingsPage {
        override val title get() = "Audio"
    }

    @Serializable
    data object Appearance : SettingsPage {
        override val title get() = "Aspetto e risparmio energetico"
    }

    @Serializable
    data object Watch : SettingsPage {
        override val title get() = "Smartwatch"
    }

    @Serializable
    data object Refresh : SettingsPage {
        override val title get() = "Aggiornamento"
    }
}

/**
 * Una pagina delle impostazioni, a tutto schermo. La principale ha stazione, Partenze/Arrivi e
 * il menu delle altre pagine, che si aprono con [onOpen]. [onBack] è la freccia in alto, come il
 * gesto di sistema: dalle pagine del menu torna alla principale, da lì al tabellone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: LiveBoardState,
    page: SettingsPage,
    knowsCharging: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        // Ogni pagina ha il suo scorrimento: si apre dall'inizio, non dove era la precedente.
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sugli schermi larghi (il tabellone sta di solito in orizzontale) una colonna stretta.
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (page) {
                    SettingsPage.Main -> MainSettings(state, onOpen)
                    SettingsPage.Audio -> AudioSettings(state)
                    SettingsPage.Appearance -> AppearanceSettings(state, knowsCharging)
                    SettingsPage.Watch -> WatchSettings(state)
                    SettingsPage.Refresh -> RefreshSettings(state)
                }
            }
        }
    }
}

@Composable
private fun MainSettings(state: LiveBoardState, onOpen: (SettingsPage) -> Unit) {
    var pickStation by rememberSaveable { mutableStateOf(false) }

    StationField(state.source, state.station, onClick = { pickStation = true })
    BoardModeSelector(state.mode, { state.mode = it }, Modifier.fillMaxWidth())
    Column {
        if (state.hasSounds) {
            MenuRow(SettingsPage.Audio.title, audioSummary(state)) { onOpen(SettingsPage.Audio) }
        }
        MenuRow(SettingsPage.Appearance.title, appearanceSummary(state)) {
            onOpen(SettingsPage.Appearance)
        }
        if (state.watchSync != null) {
            MenuRow(SettingsPage.Watch.title, watchSummary(state)) {
                onOpen(SettingsPage.Watch)
            }
        }
        MenuRow(SettingsPage.Refresh.title, refreshSummary(state)) {
            onOpen(SettingsPage.Refresh)
        }
    }
    state.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (pickStation) {
        StationDialog(
            state,
            currentSource = state.source,
            currentStation = state.station,
            onSelect = { source, station ->
                state.select(source, station)
                pickStation = false
            },
            onDismiss = { pickStation = false },
        )
    }
}

/** "RFI ogni 30 s · FNM ogni 1 min": l'intervallo di ogni fornitore (dei dettagli treni, per FNM). */
private fun refreshSummary(state: LiveBoardState): String =
    state.availableSources.joinToString(" · ") { source ->
        "${source.label} ogni ${intervalLabel(state.refreshInterval(source))}"
    }

@Composable
private fun RefreshSettings(state: LiveBoardState) {
    state.availableSources.forEach { source ->
        val listInterval = state.listRefreshInterval(source)
        IntervalSlider(
            label = if (listInterval == null) source.label else "${source.label} · dettagli treni",
            value = state.refreshInterval(source),
            options = LiveBoardState.REFRESH_OPTIONS,
            onChange = { state.setRefreshInterval(source, it) },
            max = state.maxRefreshInterval(source),
            hint = listInterval?.let { "Non più di rado dell'elenco treni" },
        )
        if (listInterval != null) {
            IntervalSlider(
                label = "${source.label} · elenco treni",
                value = listInterval,
                options = LiveBoardState.LIST_REFRESH_OPTIONS,
                onChange = { state.setListRefreshInterval(source, it) },
                min = state.minListRefreshInterval(source),
                hint = "Non più spesso dei dettagli treni",
            )
        }
    }
}

/** Il dispositivo suona qualcosa: senza, la pagina Audio non compare. */
private val LiveBoardState.hasSounds
    get() = canAnnounce || canPlayBackground || canFlapSound

/** "Annunci · Sottofondo": i suoni accesi. */
private fun audioSummary(state: LiveBoardState): String =
    listOfNotNull(
        "Annunci".takeIf { state.canAnnounce && state.announce },
        "Sottofondo".takeIf { state.canPlayBackground && state.background },
        "Palette".takeIf {
            state.canFlapSound && state.flapSound && state.boardStyle == BoardStyle.SplitFlap
        },
    ).joinToString(" · ").ifEmpty { "Nessun suono" }

/** "LED · risparmio · fondo nero". */
private fun appearanceSummary(state: LiveBoardState): String =
    listOfNotNull(
        if (state.boardStyle == BoardStyle.Led) "LED" else "Palette",
        "risparmio".takeIf { state.ecoMode },
        "fondo nero".takeIf { state.oledPalette },
    ).joinToString(" · ")

@Composable
private fun AudioSettings(state: LiveBoardState) {
    if (state.canAnnounce) {
        SwitchRow("Annunci sonori", state.announce) { state.announce = it }
        if (state.announce) {
            VolumeSlider(
                label = "Volume degli annunci",
                value = state.announceVolume,
                options = LiveBoardState.ANNOUNCE_VOLUME_OPTIONS,
                onChange = { state.announceVolume = it },
                hint = "Jingle e voce, rispetto al volume del dispositivo",
            )
        }
    }
    if (state.canPlayBackground) {
        SwitchRow("Sottofondo di stazione", state.background) { state.background = it }
        if (state.background) {
            VolumeSlider(
                label = "Volume del sottofondo",
                value = state.backgroundVolume,
                options = BACKGROUND_VOLUME_OPTIONS,
                onChange = { state.backgroundVolume = it },
                hint = buildString {
                    append("Tace di notte (${BackgroundSound.QUIET_START}–${BackgroundSound.QUIET_END})")
                    if (state.canAnnounce && state.announce) {
                        append("; scende al ${(BackgroundSound.DUCKED_VOLUME * 100).roundToInt()}% durante gli annunci")
                    }
                },
            )
        }
    }
    if (state.canFlapSound) {
        val flaps = state.boardStyle == BoardStyle.SplitFlap
        SwitchRow(
            "Suono delle palette",
            state.flapSound,
            hint = if (flaps) null else "Solo con il tabellone a palette (Aspetto)",
        ) { state.flapSound = it }
        if (state.flapSound) {
            VolumeSlider(
                label = "Volume delle palette",
                value = state.flapVolume,
                options = LiveBoardState.FLAP_VOLUME_OPTIONS,
                onChange = {
                    state.flapVolume = it
                    if (!state.muted) state.flapClatter?.preview()
                },
                hint = if (state.canAnnounce && state.announce) {
                    "Scende al ${(FlapClatter.DUCKED_VOLUME * 100).roundToInt()}% durante gli annunci"
                } else null,
            )
        }
    }
}

/** Selettore tra partenze e arrivi, nelle impostazioni e nelle schede della mappa. */
@Composable
internal fun BoardModeSelector(
    mode: BoardMode,
    onChange: (BoardMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        BoardMode.entries.forEachIndexed { i, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(i, BoardMode.entries.size),
            ) {
                Text(
                    stringResource(
                        if (entry == BoardMode.Departures) Res.string.board_departures
                        else Res.string.board_arrivals
                    )
                )
            }
        }
    }
}

@Composable
private fun AppearanceSettings(state: LiveBoardState, knowsCharging: Boolean) {
    SectionTitle("Tabellone")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BoardStyle.entries.forEachIndexed { i, style ->
            SegmentedButton(
                selected = state.boardStyle == style,
                onClick = { state.boardStyle = style },
                shape = SegmentedButtonDefaults.itemShape(i, BoardStyle.entries.size),
            ) { Text(if (style == BoardStyle.Led) "LED" else "Palette") }
        }
    }
    SwitchRow("Scorrimento automatico", state.autoScroll) { state.autoScroll = it }
    if (state.boardStyle == BoardStyle.Led) {
        IntervalSlider(
            label = "Lampeggio del treno annunciato",
            value = state.announceBlink,
            options = LiveBoardState.ANNOUNCE_BLINK_OPTIONS,
            onChange = { state.announceBlink = it },
            hint = if (state.canAnnounce && state.announce) {
                "Da quando comincia il jingle"
            } else {
                "Da quando il tabellone mostra la variazione"
            },
        )
    }
    if (state.boardStyle == BoardStyle.SplitFlap) {
        IntervalSlider(
            label = "Pausa tra le pagine",
            value = state.flapPagePause,
            options = LiveBoardState.FLAP_PAGE_PAUSE_OPTIONS,
            onChange = { state.flapPagePause = it },
            hint = "Dalla fine della girata; doppia in modalità risparmio",
        )
    }
    SwitchRow("Intro all'avvio", state.intro) { state.intro = it }
    SwitchRow("Tabellone nel cruscotto", state.dashboard) { state.dashboard = it }
    SectionTitle("Risparmio energetico")
    SwitchRow("Modalità risparmio", state.ecoMode) { state.ecoMode = it }
    SwitchRow("Fondo nero (schermi OLED)", state.oledPalette) { state.oledPalette = it }
    if (knowsCharging) {
        SwitchRow("Schermo acceso solo in carica", state.screenOnOnlyWhenCharging) {
            state.screenOnOnlyWhenCharging = it
        }
    }
}

@Composable
private fun WatchSettings(state: LiveBoardState) {
    val sync = state.watchSync ?: return
    var pickStation by rememberSaveable { mutableStateOf(false) }

    /** Stazione di cui scegliere il treno da aggiungere; null a dialogo chiuso. */
    var pickTrain by remember { mutableStateOf<Pair<StationSource, Station>?>(null) }
    val connected by sync.connected.collectAsState()

    Text(
        "Il watch mostra un treno alla volta, nell'ordine delle partenze: il successivo quando " +
                "il precedente sparisce dal tabellone. Gli stessi treni si seguono ogni giorno.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.watchTrains.isEmpty()) {
        Text(
            "Nessun treno",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column {
            state.watchTrains.forEach { train ->
                WatchTrainRow(train, onRemove = { state.unfollowOnWatch(train) })
            }
        }
    }
    OutlinedButton(onClick = { pickStation = true }) { Text("Aggiungi un treno") }
    if (!connected) {
        Text(
            "Nessuno smartwatch collegato: i treni arriveranno al prossimo collegamento",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (pickStation) {
        StationDialog(
            state,
            currentSource = state.source,
            currentStation = state.station,
            onSelect = { source, station ->
                pickStation = false
                pickTrain = source to station
            },
            onDismiss = { pickStation = false },
        )
    }

    pickTrain?.let { (source, station) ->
        TrainDialog(
            state,
            source,
            station,
            followed = state.watchTrains.filter { it.source == source && it.station.id == station.id }
                .mapTo(HashSet()) { it.trainNumber },
            onSelect = { train ->
                state.followOnWatch(train)
                pickTrain = null
            },
            onDismiss = { pickTrain = null },
        )
    }
}

/** Un treno seguito: etichetta, stazione e orario (o che lo si aspetta), e il tasto per toglierlo. */
@Composable
private fun WatchTrainRow(train: WatchSelection, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(train.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${train.station.name} · ${train.time ?: "orario quando comparirà in tabellone"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) { Icon(Close, contentDescription = "Togli ${train.label}") }
    }
}

private fun watchSummary(state: LiveBoardState): String = when (state.watchTrains.size) {
    0 -> "Nessun treno"
    1 -> state.watchTrains.single().label
    else -> "${state.watchTrains.size} treni"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Voce del menu che apre un'altra pagina; [summary] riassume cosa c'è dentro. */
@Composable
private fun MenuRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(ChevronRight, contentDescription = null)
    }
}

/** Icone Material (24×24) dal loro tracciato, per non dipendere dalla libreria delle icone. */
private fun materialIcon(path: String) = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

internal val ArrowBack =
    materialIcon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
private val ChevronRight =
    materialIcon("M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
internal val Close =
    materialIcon("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
internal val Fullscreen =
    materialIcon("M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
internal val FullscreenExit =
    materialIcon("M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z")
internal val Search =
    materialIcon("M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Cursore a scatti tra tutte le [options], che però si ferma a [min] e [max]: oltre non prosegue.
 * Durante il trascinamento cambia solo l'etichetta, il valore arriva a [onChange] al rilascio
 * (così il ciclo di aggiornamento non riparte a ogni scatto).
 */
@Composable
private fun IntervalSlider(
    label: String,
    value: Duration,
    options: List<Duration>,
    onChange: (Duration) -> Unit,
    min: Duration? = null,
    max: Duration? = null,
    hint: String? = null,
) {
    val allowed = allowedIndices(options, min, max)
    var index by remember(value, options) {
        mutableIntStateOf(
            options.indexOf(value).coerceAtLeast(0)
        )
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                intervalLabel(options.getOrElse(index) { value }),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Slider(
            value = index.toFloat(),
            onValueChange = { index = it.roundToInt().coerceIn(allowed) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            onValueChangeFinished = { if (options[index] != value) onChange(options[index]) },
        )
    }
}

/**
 * Cursore a scatti tra i volumi [options] (percentuali). Come [IntervalSlider], il valore arriva
 * a [onChange] solo al rilascio: un'anteprima a ogni scatto si accavallerebbe.
 */
@Composable
private fun VolumeSlider(
    label: String,
    value: Int,
    options: List<Int>,
    onChange: (Int) -> Unit,
    hint: String? = null,
) {
    var index by remember(value, options) {
        mutableIntStateOf(
            options.indexOf(value).coerceAtLeast(0)
        )
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("${options[index]}%", style = MaterialTheme.typography.bodyLarge)
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Slider(
            value = index.toFloat(),
            onValueChange = { index = it.roundToInt().coerceIn(options.indices) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            onValueChangeFinished = { if (options[index] != value) onChange(options[index]) },
        )
    }
}

/** Posizioni di [options] raggiungibili tra [min] e [max] (inclusi); tutte se non ci sono limiti. */
internal fun allowedIndices(options: List<Duration>, min: Duration?, max: Duration?): IntRange {
    val first = if (min == null) 0 else options.indexOfFirst { it >= min }.takeIf { it >= 0 }
        ?: options.lastIndex
    val last =
        if (max == null) options.lastIndex else options.indexOfLast { it <= max }.takeIf { it >= 0 }
            ?: 0
    return first..maxOf(first, last)
}

/** "45 s", "1 min", "1 min 30 s". */
internal fun intervalLabel(interval: Duration): String {
    val minutes = interval.inWholeMinutes
    val seconds = interval.inWholeSeconds % 60
    return when {
        minutes == 0L -> "$seconds s"
        seconds == 0L -> "$minutes min"
        else -> "$minutes min $seconds s"
    }
}

/** Campo di sola lettura: un clic apre la scelta della stazione. */
@Composable
private fun StationField(source: StationSource, station: Station, onClick: () -> Unit) =
    ReadOnlyField(station.name, "Stazione (${source.label})", onClick)

/** Campo di sola lettura che a un clic apre una scelta. */
@Composable
private fun ReadOnlyField(value: String, label: String, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Sopra il campo, così il clic non lo mette a fuoco e arriva sempre a onClick.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

/**
 * Elenco delle stazioni, una scheda per fornitore (RFI ~2400 località, FNM ~560), filtrabile dalla
 * barra di ricerca, a partire da [currentStation] di [currentSource]. Scegliere una stazione di
 * un'altra scheda cambia anche il fornitore.
 */
@Composable
private fun StationDialog(
    state: LiveBoardState,
    currentSource: StationSource,
    currentStation: Station,
    onSelect: (StationSource, Station) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(currentSource) }
    LaunchedEffect(tab) { if (state.stations(tab).isEmpty()) state.loadStations(tab) }
    var query by rememberSaveable { mutableStateOf("") }
    val stations = state.stations(tab)
    val stationsError = state.stationsError(tab)
    val current = currentStation.takeIf { tab == currentSource }
    val matches = remember(stations, query) {
        val search = SearchText(query)
        if (search.isEmpty) stations else stations.filter { search.matches(it.name) }
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
                                    selected = station.id == current?.id,
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

/**
 * Treni in partenza da [station] tra cui scegliere quello da aggiungere a quelli seguiti sullo
 * smartwatch; [followed] sono i numeri dei treni di questa stazione già seguiti.
 *
 * Il numero scritto filtra l'elenco; se il treno non c'è (non è ancora in tabellone, o le partenze
 * non si leggono) lo si segue lo stesso, ed etichetta e orario arrivano quando compare.
 */
@Composable
private fun TrainDialog(
    state: LiveBoardState,
    source: StationSource,
    station: Station,
    followed: Set<String>,
    onSelect: (WatchSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    val typed = number.trim().uppercase()
    var attempt by remember { mutableIntStateOf(0) }
    val trains by produceState<Result<List<WatchedTrain>>?>(null, source, station, attempt) {
        value = null
        value = try {
            Result.success(state.watchCandidates(source, station))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun followTyped() {
        if (typed.isEmpty()) return
        // In elenco c'è già tutto; altrimenti i dati arrivano quando il treno compare.
        val train = trains?.getOrNull()?.firstOrNull { it.number == typed }
        onSelect(
            if (train != null) WatchSelection.of(source, station, train)
            else WatchSelection.byNumber(source, station, typed)
        )
    }

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
                Column(
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Aggiungi un treno", style = MaterialTheme.typography.headlineSmall)
                    Text(station.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Scegli tra le partenze delle prossime ore o scrivi il numero di un treno " +
                                "che non c'è ancora: destinazione e orario arriveranno quando " +
                                "comparirà in tabellone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            label = { Text("Numero del treno") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { followTyped() }),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = ::followTyped,
                            enabled = typed.isNotEmpty()
                        ) { Text("Segui") }
                    }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val result = trains
                    val list = result?.getOrNull()
                    when {
                        result == null -> CircularProgressIndicator(Modifier.size(32.dp))
                        list == null -> Text(
                            "Partenze non disponibili: ${result.exceptionOrNull()?.message ?: ""}. " +
                                    "Si può comunque scrivere il numero del treno.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(list.filter { it.number.startsWith(typed) }) { train ->
                                TrainItem(
                                    title = "${train.category} ${train.number} · ${train.time}",
                                    subtitle = train.destination,
                                    selected = train.number in followed,
                                    onClick = {
                                        onSelect(
                                            WatchSelection.of(
                                                source,
                                                station,
                                                train
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (list != null && typed.isNotEmpty() && list.none { it.number.startsWith(typed) }) {
                        Text(
                            "Non è tra le partenze: con «Segui» lo si aspetta",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (trains?.isFailure == true) {
                        TextButton(onClick = { attempt++ }) { Text("Riprova") }
                    }
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                }
            }
        }
    }
}

@Composable
private fun TrainItem(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val color =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = color,
        )
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = color) }
    }
}

/** Nome del fornitore nelle schede e nelle etichette. */
internal val StationSource.label
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
