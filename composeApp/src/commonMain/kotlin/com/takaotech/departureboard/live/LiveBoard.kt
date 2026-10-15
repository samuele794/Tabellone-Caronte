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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.takaotech.departureboard.BoardColors
import com.takaotech.departureboard.BoardFooter
import com.takaotech.departureboard.BoardLabels
import com.takaotech.departureboard.BoardLayout
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.DepartureBoard
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.LocalFlapClatter
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.BoardAnnouncements
import com.takaotech.departureboard.announce.PlaceNames
import com.takaotech.departureboard.cache.StationCache
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.live.LiveBoardState.Companion.ANNOUNCE_BLINK_OPTIONS
import com.takaotech.departureboard.live.LiveBoardState.Companion.ANNOUNCE_VOLUME_OPTIONS
import com.takaotech.departureboard.live.LiveBoardState.Companion.DEFAULT_REFRESH
import com.takaotech.departureboard.live.LiveBoardState.Companion.FLAP_PAGE_PAUSE_OPTIONS
import com.takaotech.departureboard.live.LiveBoardState.Companion.FLAP_VOLUME_OPTIONS
import com.takaotech.departureboard.live.LiveBoardState.Companion.STALE_PAUSE
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Stato del tabellone collegato a uno dei [sources] (RFI, FNM…), scelto insieme alla stazione.
 * Con un [announcer] le variazioni tra un aggiornamento e il successivo vengono annunciate a voce;
 * con una [stationCache] gli elenchi stazioni si scaricano al più una volta al mese; con un
 * [settingsStore] stazione, scorrimento, annunci e intervalli di aggiornamento si ritrovano alla
 * riapertura dell'app. Con un [flapClatter] il tabellone a palette crepita quando gira.
 */
@Stable
class LiveBoardState(
    private val sources: Map<StationSource, BoardSource>,
    initialSource: StationSource = StationSource.Rfi,
    initial: Station = sources.getValue(initialSource).defaultStation,
    private val announcer: Announcer? = null,
    private val stationCache: StationCache? = null,
    private val settingsStore: SettingsStore? = null,
    /** Suono delle palette; null = la piattaforma non lo riproduce. */
    val flapClatter: FlapClatter? = null,
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
    private var autoScrollState by mutableStateOf(true)
    private var announceState by mutableStateOf(true)
    private var ecoModeState by mutableStateOf(false)
    private var screenOnOnlyWhenChargingState by mutableStateOf(false)
    private var oledPaletteState by mutableStateOf(false)
    private var boardStyleState by mutableStateOf(BoardStyle.Led)
    private var flapSoundState by mutableStateOf(true)
    private var flapVolumeState by mutableIntStateOf(DEFAULT_FLAP_VOLUME)
    private var flapPagePauseState by mutableStateOf(DEFAULT_FLAP_PAGE_PAUSE)
    private var announceBlinkState by mutableStateOf(DEFAULT_ANNOUNCE_BLINK)
    private var announceVolumeState by mutableIntStateOf(DEFAULT_ANNOUNCE_VOLUME)

    /**
     * Se le impostazioni salvate sono già state ripristinate (o non c'è niente da ripristinare):
     * fino ad allora il tabellone non scarica nulla per la stazione iniziale.
     */
    var restored by mutableStateOf(settingsStore == null)
        private set

    /** Impostazioni cambiate prima del ripristino: vincono su quelle salvate. */
    private val changedBeforeRestore = mutableSetOf<Setting>()

    private enum class Setting {
        Selection, AutoScroll, Announce, Refresh, ListRefresh, EcoMode, ScreenOnOnlyWhenCharging, OledPalette,
        BoardStyle, FlapSound, FlapVolume, FlapPagePause, AnnounceBlink, AnnounceVolume,
    }

    /** Intervalli di aggiornamento scelti, per fornitore; chi manca usa [DEFAULT_REFRESH]. */
    private var refreshIntervals by mutableStateOf<Map<StationSource, Duration>>(emptyMap())

    /** Ogni quanto si riscarica il tabellone di [source] (per FNM: i dettagli dei treni). */
    fun refreshInterval(source: StationSource = this.source): Duration =
        refreshIntervals[source] ?: DEFAULT_REFRESH

    /** Cambia l'intervallo dei dettagli, senza andare oltre [maxRefreshInterval]. */
    fun setRefreshInterval(source: StationSource, interval: Duration) {
        val max = maxRefreshInterval(source)
        refreshIntervals += source to if (max == null) interval else interval.coerceAtMost(max)
        changed(Setting.Refresh)
    }

    /** Intervallo predefinito dell'elenco treni, per i fornitori che lo riscaricano a parte. */
    private val defaultListRefresh: Map<StationSource, Duration> =
        sources.mapNotNull { (source, client) -> client.listRefresh?.let { source to it } }.toMap()

    /** Intervalli dell'elenco treni scelti, per fornitore; chi manca usa il suo predefinito. */
    private var listRefreshIntervals by mutableStateOf<Map<StationSource, Duration>>(emptyMap())

    /** Ogni quanto si riscarica l'elenco treni di [source]; null se non lo riscarica a parte. */
    fun listRefreshInterval(source: StationSource): Duration? =
        listRefreshIntervals[source] ?: defaultListRefresh[source]

    /** Cambia l'intervallo dell'elenco treni, senza scendere sotto [minListRefreshInterval]. */
    fun setListRefreshInterval(source: StationSource, interval: Duration) {
        val min = minListRefreshInterval(source) ?: return
        applyListRefresh(listRefreshIntervals + (source to interval.coerceAtLeast(min)))
        changed(Setting.ListRefresh)
    }

    /**
     * L'intervallo più lungo per i dettagli di [source]: non oltre l'elenco treni, che non deve
     * riscaricarsi più spesso dei dettagli (senza un elenco aggiornato non avrebbero treni nuovi
     * da chiedere). null se il fornitore non riscarica l'elenco a parte.
     */
    fun maxRefreshInterval(source: StationSource): Duration? {
        val list = listRefreshInterval(source) ?: return null
        return REFRESH_OPTIONS.lastOrNull { it <= list } ?: list
    }

    /** L'intervallo più breve per l'elenco treni di [source]: non sotto i dettagli; null se non c'è. */
    fun minListRefreshInterval(source: StationSource): Duration? {
        if (listRefreshInterval(source) == null) return null
        val details = refreshInterval(source)
        return LIST_REFRESH_OPTIONS.firstOrNull { it >= details } ?: details
    }

    /** Abbassa i dettagli di [source] se superano l'elenco treni; true se li ha cambiati. */
    private fun clampRefresh(source: StationSource): Boolean {
        val max = maxRefreshInterval(source) ?: return false
        if (refreshInterval(source) <= max) return false
        refreshIntervals += source to max
        return true
    }

    /** Tiene gli intervalli dei soli fornitori che li supportano e li passa ai fornitori. */
    private fun applyListRefresh(intervals: Map<StationSource, Duration>) {
        listRefreshIntervals = intervals.filterKeys { it in defaultListRefresh }
        listRefreshIntervals.forEach { (source, interval) ->
            sources.getValue(source).listRefresh = interval
        }
        // Il conto alla rovescia dell'elenco si sposta subito, senza aspettare il prossimo giro.
        if (nextListRefreshAt != null) nextListRefreshAt =
            sources.getValue(source).nextListRefreshAt
    }

    /** Cambia fornitore e stazione insieme. */
    fun select(source: StationSource, station: Station) {
        require(source in sources) { "Fornitore $source non disponibile" }
        this.source = source
        this.station = station
        changed(Setting.Selection)
    }

    /** Scorrimento automatico delle righe che non stanno nel tabellone. */
    var autoScroll: Boolean
        get() = autoScrollState
        set(value) {
            autoScrollState = value
            changed(Setting.AutoScroll)
        }

    /** Annunci sonori attivi; spegnendoli si interrompe anche quello in corso. */
    var announce: Boolean
        get() = announceState
        set(value) {
            announceState = value
            if (!value) announcer?.clear()
            changed(Setting.Announce)
        }

    /** Modalità risparmio: marquee e scorrimento a 5 scatti al secondo, ":" dell'orologio fissi. */
    var ecoMode: Boolean
        get() = ecoModeState
        set(value) {
            ecoModeState = value
            changed(Setting.EcoMode)
        }

    /** Schermo sempre acceso solo in carica, sulle piattaforme che sanno se lo sono. */
    var screenOnOnlyWhenCharging: Boolean
        get() = screenOnOnlyWhenChargingState
        set(value) {
            screenOnOnlyWhenChargingState = value
            changed(Setting.ScreenOnOnlyWhenCharging)
        }

    /** Fondo e moduli neri ([BoardColors.Oled]). */
    var oledPalette: Boolean
        get() = oledPaletteState
        set(value) {
            oledPaletteState = value
            changed(Setting.OledPalette)
        }

    /** Aspetto del tabellone: LED o palette. */
    var boardStyle: BoardStyle
        get() = boardStyleState
        set(value) {
            boardStyleState = value
            changed(Setting.BoardStyle)
        }

    /** Crepitio delle palette quando girano (solo con [boardStyle] a palette). */
    var flapSound: Boolean
        get() = flapSoundState
        set(value) {
            flapSoundState = value
            changed(Setting.FlapSound)
        }

    /**
     * Volume del crepitio delle palette, in percentuale; agganciato all'opzione più vicina di
     * [FLAP_VOLUME_OPTIONS].
     */
    var flapVolume: Int
        get() = flapVolumeState
        set(value) {
            applyFlapVolume(value)
            changed(Setting.FlapVolume)
        }

    private fun applyFlapVolume(percent: Int) {
        flapVolumeState = FLAP_VOLUME_OPTIONS.minBy { abs(it - percent) }
        flapClatter?.volume = flapVolumeState / 100f
    }

    /**
     * Volume degli annunci (jingle e voce), in percentuale; agganciato all'opzione più vicina di
     * [ANNOUNCE_VOLUME_OPTIONS]. Vale dall'annuncio seguente.
     */
    var announceVolume: Int
        get() = announceVolumeState
        set(value) {
            applyAnnounceVolume(value)
            changed(Setting.AnnounceVolume)
        }

    private fun applyAnnounceVolume(percent: Int) {
        announceVolumeState = ANNOUNCE_VOLUME_OPTIONS.minBy { abs(it - percent) }
        announcer?.volume = announceVolumeState / 100f
    }

    /**
     * Pausa su ogni pagina di un testo lungo nel tabellone a palette, contata dalla fine della
     * girata; agganciata all'opzione più vicina di [FLAP_PAGE_PAUSE_OPTIONS].
     */
    var flapPagePause: Duration
        get() = flapPagePauseState
        set(value) {
            flapPagePauseState = value.nearestOf(FLAP_PAGE_PAUSE_OPTIONS)
            changed(Setting.FlapPagePause)
        }

    /**
     * Quanto lampeggia (a LED) la riga del treno annunciato, da quando comincia il jingle (o
     * da quando si vede la variazione, con gli annunci sonori spenti);
     * agganciata all'opzione più vicina di [ANNOUNCE_BLINK_OPTIONS].
     */
    var announceBlink: Duration
        get() = announceBlinkState
        set(value) {
            announceBlinkState = value.nearestOf(ANNOUNCE_BLINK_OPTIONS)
            changed(Setting.AnnounceBlink)
        }

    /**
     * Abbassa il crepitio delle palette mentre si diffonde un annuncio
     * ([FlapClatter.DUCKED_VOLUME]); gira finché non viene cancellata.
     */
    suspend fun duckFlapsDuringAnnouncements() {
        val clatter = flapClatter ?: return
        val announcer = announcer ?: return
        try {
            announcer.speaking.collect { clatter.ducked = it }
        } finally {
            clatter.ducked = false
        }
    }

    /** Lampeggi in corso per numero di treno: più lampeggi dello stesso treno si accavallano. */
    private var blinkCounts by mutableStateOf<Map<String, Int>>(emptyMap())

    /** Numeri dei treni annunciati la cui riga lampeggia in questo momento. */
    val blinkingTrains: Set<String> get() = blinkCounts.keys

    /** Treni da far lampeggiare, raccolti da [blinkAnnouncedRows]. */
    private val blinkRequests = Channel<List<String>>(Channel.UNLIMITED)

    /**
     * Fa lampeggiare per [announceBlink] la riga di ogni annuncio: quando comincia a sentirsi
     * (col jingle) o, se gli annunci sonori sono spenti, appena il tabellone mostra la
     * variazione. Gira finché non viene cancellata.
     */
    suspend fun blinkAnnouncedRows(): Unit = coroutineScope {
        announcer?.let { announcer ->
            launch {
                announcer.started.collect { blinkRequests.trySend(listOf(it.train.number)) }
            }
        }
        try {
            for (numbers in blinkRequests) {
                val duration = announceBlink
                numbers.forEach { number -> launch { blink(number, duration) } }
            }
        } finally {
            blinkCounts = emptyMap()
        }
    }

    private suspend fun blink(number: String, duration: Duration) {
        blinkCounts += number to (blinkCounts[number] ?: 0) + 1
        try {
            delay(duration)
        } finally {
            val left = (blinkCounts[number] ?: 1) - 1
            blinkCounts = if (left > 0) blinkCounts + (number to left) else blinkCounts - number
        }
    }

    /** [departures] con le righe dei treni annunciati ([blinkingTrains]) che lampeggiano. */
    val boardDepartures: List<Departure>
        get() = blinkingTrains.takeIf { it.isNotEmpty() }?.let { numbers ->
            departures.map { if (it.number in numbers) it.copy(blink = true) else it }
        } ?: departures

    /** Se il tabellone ha un motore per il suono delle palette. */
    val canFlapSound get() = flapClatter != null

    /** Riporta le impostazioni a quelle salvate; dalla seconda volta non fa nulla. */
    suspend fun restoreSettings() {
        if (restored) return
        val saved = settingsStore?.load() ?: BoardSettings()
        val selection = saved.selection
        if (selection != null && selection.source in sources && Setting.Selection !in changedBeforeRestore) {
            source = selection.source
            station = selection.station
        }
        if (Setting.AutoScroll !in changedBeforeRestore) autoScrollState = saved.autoScroll
        if (Setting.Announce !in changedBeforeRestore) announceState = saved.announce
        if (Setting.EcoMode !in changedBeforeRestore) ecoModeState = saved.ecoMode
        if (Setting.ScreenOnOnlyWhenCharging !in changedBeforeRestore) {
            screenOnOnlyWhenChargingState = saved.screenOnOnlyWhenCharging
        }
        if (Setting.OledPalette !in changedBeforeRestore) oledPaletteState = saved.oledPalette
        if (Setting.BoardStyle !in changedBeforeRestore) boardStyleState = saved.boardStyle
        if (Setting.FlapSound !in changedBeforeRestore) flapSoundState = saved.flapSound
        if (Setting.FlapVolume !in changedBeforeRestore) applyFlapVolume(saved.flapVolume)
        if (Setting.FlapPagePause !in changedBeforeRestore) {
            flapPagePauseState = saved.flapPageSeconds.seconds.nearestOf(FLAP_PAGE_PAUSE_OPTIONS)
        }
        if (Setting.AnnounceVolume !in changedBeforeRestore) applyAnnounceVolume(saved.announceVolume)
        if (Setting.AnnounceBlink !in changedBeforeRestore) {
            announceBlinkState =
                saved.announceBlinkSeconds.seconds.nearestOf(ANNOUNCE_BLINK_OPTIONS)
        }
        // Per gli intervalli vince, fornitore per fornitore, quello scelto prima del ripristino.
        val refresh = saved.refreshSeconds.toIntervals(REFRESH_OPTIONS).filterKeys { it in sources }
        refreshIntervals =
            if (Setting.Refresh in changedBeforeRestore) refresh + refreshIntervals else refresh
        val listRefresh = saved.listRefreshSeconds.toIntervals(LIST_REFRESH_OPTIONS)
        applyListRefresh(
            if (Setting.ListRefresh in changedBeforeRestore) listRefresh + listRefreshIntervals else listRefresh
        )
        // Un file scritto a mano, o un cambio prima del ripristino, può avere i dettagli più
        // radi dell'elenco: si tiene l'elenco e si abbassano i dettagli.
        val clamped = defaultListRefresh.keys.map(::clampRefresh).any { it }
        restored = true
        if (changedBeforeRestore.isNotEmpty() || clamped) save()
    }

    private fun changed(setting: Setting) {
        if (restored) save() else changedBeforeRestore += setting
    }

    private fun save() {
        settingsStore?.save(
            BoardSettings(
                Selection(source, station),
                autoScroll,
                announce,
                refreshSeconds = refreshIntervals.inSeconds(),
                listRefreshSeconds = listRefreshIntervals.inSeconds(),
                ecoMode = ecoMode,
                screenOnOnlyWhenCharging = screenOnOnlyWhenCharging,
                oledPalette = oledPalette,
                boardStyle = boardStyle,
                flapSound = flapSound,
                flapVolume = flapVolume,
                flapPageSeconds = flapPagePause.inWholeSeconds.toInt(),
                announceBlinkSeconds = announceBlink.inWholeSeconds.toInt(),
                announceVolume = announceVolume,
            )
        )
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

    /** Aggiornamenti chiesti a mano, in attesa che [waitForRefresh] li raccolga. */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    /** Si può chiedere un aggiornamento a mano: impostazioni ripristinate e nessun download in corso. */
    val canRefreshNow get() = restored && !loading

    /** Aggiorna subito: [waitForRefresh] smette di attendere e il conto alla rovescia riparte. */
    fun requestRefresh() {
        if (canRefreshNow) refreshRequests.trySend(Unit)
    }

    /**
     * Attende [after] prima del prossimo aggiornamento, rendendolo visibile in [nextRefreshAt];
     * finisce prima se nel frattempo arriva [requestRefresh].
     */
    suspend fun waitForRefresh(after: Duration) {
        nextRefreshAt = TimeSource.Monotonic.markNow() + after
        withTimeoutOrNull(after) { refreshRequests.receive() }
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

    /** Da quando il tabellone non è visibile; null se lo è. */
    private var pausedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Il tabellone non è più visibile: si zittisce l'annuncio in corso. Se la pausa dura più di
     * [STALE_PAUSE], il primo tabellone al ritorno fa solo da base, così le variazioni accumulate
     * nel frattempo non si annunciano in blocco (una rotazione invece non perde nulla).
     */
    fun pause() {
        announcer?.clear()
        pausedAt = TimeSource.Monotonic.markNow()
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
        // Una richiesta arrivata mentre l'attesa era già finita la soddisfa questo aggiornamento.
        refreshRequests.tryReceive()
        var cancelled = false
        try {
            val board = client.board(station, key.third)
            departures = mapper.departures(board)
            notices = board.notices
            if (pausedAt?.let { it.elapsedNow() > STALE_PAUSE } == true) announcements.reset()
            pausedAt = null
            // Il primo tabellone di una stazione fa solo da base: update() non annuncia nulla.
            val news = announcements.update(key.second, key.third, board)
            when {
                news.isEmpty() -> Unit
                announce && announcer != null -> announcer.enqueue(news)
                else -> {
                    DebugLog.d(Announcer.TAG) { "Annunci spenti: scartati ${news.joinToString { it.code }}" }
                    // Senza voce le righe lampeggiano subito, tutte insieme.
                    blinkRequests.trySend(news.map { it.train.number }.distinct())
                }
            }
            loadedKey = key
            updatedAt = localNow()
            error = null
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            error = "Aggiornamento non riuscito: ${e.message ?: e::class.simpleName}"
        } finally {
            loading = false
            // Un aggiornamento interrotto (es. intervallo cambiato) non rimanda il prossimo.
            if (!cancelled) lastRefresh = TimeSource.Monotonic.markNow()
            nextListRefreshAt = client.nextListRefreshAt
        }
    }

    companion object {
        /** Volume degli annunci se non se ne è scelto un altro, in percentuale. */
        const val DEFAULT_ANNOUNCE_VOLUME = 100

        /** Volumi degli annunci tra cui scegliere, in percentuale. */
        val ANNOUNCE_VOLUME_OPTIONS = (10..100 step 10).toList()

        /** Lampeggio della riga del treno annunciato se non se ne è scelto un altro. */
        val DEFAULT_ANNOUNCE_BLINK = 10.seconds

        /** Durate del lampeggio della riga del treno annunciato tra cui scegliere. */
        val ANNOUNCE_BLINK_OPTIONS = (10..30).map { it.seconds }

        /** Pausa oltre la quale gli annunci ripartono da una nuova base. */
        val STALE_PAUSE = 1.minutes

        /** Intervallo di aggiornamento se non se ne è scelto un altro. */
        val DEFAULT_REFRESH = 30.seconds

        /** Intervalli di aggiornamento tra cui scegliere. */
        val REFRESH_OPTIONS = listOf(
            15.seconds, 20.seconds, 30.seconds, 45.seconds,
            1.minutes, 90.seconds, 2.minutes, 3.minutes, 5.minutes,
        )

        /** Intervalli di riscaricamento dell'elenco treni tra cui scegliere. */
        val LIST_REFRESH_OPTIONS = listOf(1, 2, 3, 5, 10, 15, 30).map { it.minutes }

        /** Volume delle palette se non se ne è scelto un altro, in percentuale. */
        const val DEFAULT_FLAP_VOLUME = 100

        /** Volumi delle palette tra cui scegliere, in percentuale. */
        val FLAP_VOLUME_OPTIONS = (10..100 step 10).toList()

        /** Pausa sulle pagine dei testi lunghi se non se ne è scelta un'altra. */
        val DEFAULT_FLAP_PAGE_PAUSE = 5.seconds

        /** Pause sulle pagine dei testi lunghi tra cui scegliere. */
        val FLAP_PAGE_PAUSE_OPTIONS = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30).map { it.seconds }
    }
}

/** Secondi salvati agganciati all'opzione più vicina, anche se il file è stato scritto a mano. */
private fun Map<StationSource, Int>.toIntervals(options: List<Duration>): Map<StationSource, Duration> =
    mapValues { (_, seconds) -> seconds.seconds.nearestOf(options) }

private fun Duration.nearestOf(options: List<Duration>): Duration =
    options.minBy { (it - this).absoluteValue }

private fun Map<StationSource, Duration>.inSeconds(): Map<StationSource, Int> =
    mapValues { it.value.inWholeSeconds.toInt() }

/**
 * Tabellone live a tutto schermo; stazione, modalità e scorrimento si cambiano dalla rotella.
 *
 * Gli aggiornamenti si fermano mentre l'app non è visibile (sotto [Lifecycle.State.STARTED]) e
 * ripartono al ritorno. [charging] dice se il dispositivo è in carica; null se la piattaforma
 * non lo sa, e allora lo schermo resta sempre acceso.
 */
@Composable
fun LiveBoard(state: LiveBoardState, now: LocalDateTime, charging: Boolean? = null) {
    LaunchedEffect(Unit) { state.restoreSettings() }
    LaunchedEffect(state) { state.duckFlapsDuringAnnouncements() }
    LaunchedEffect(state) { state.blinkAnnouncedRows() }
    LaunchedEffect(state.source, state.restored) {
        if (state.restored && state.stations(state.source).isEmpty()) state.loadStations()
    }
    val interval = state.refreshInterval()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(
        state.source,
        state.station.id,
        state.mode,
        state.restored,
        interval,
        lifecycle
    ) {
        if (!state.restored) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                // Cambiando intervallo, o tornando in primo piano, si attende solo il resto
                // dall'ultimo aggiornamento (niente, se è già passato).
                state.waitForRefresh(state.nextRefreshIn(interval))
                while (isActive) {
                    state.refresh()
                    state.waitForRefresh(interval)
                }
            } finally {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) state.pause()
            }
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val colors = remember(state.oledPalette) {
        if (state.oledPalette) BoardColors.Oled else BoardColors()
    }
    val screenOn = !state.screenOnOnlyWhenCharging || charging != false
    val clatter =
        state.flapClatter.takeIf { state.boardStyle == BoardStyle.SplitFlap && state.flapSound }
    Box(
        Modifier.fillMaxSize().background(colors.frame).safeDrawingPadding()
            .then(if (screenOn) Modifier.keepScreenOn() else Modifier)
    ) {
        CompositionLocalProvider(LocalFlapClatter provides clatter) {
            DepartureBoard(
                departures = state.boardDepartures,
                footer = boardFooter(now, state.notices, state.station.name),
                labels = if (state.mode == BoardMode.Arrivals) BoardLabels.Arrivals else BoardLabels(),
                layout = BoardLayout(
                    rows = null,
                    autoScroll = state.autoScroll,
                    logoCols = RfiMapper.LOGO_COLS,
                    logoDensity = RfiMapper.LOGO_DENSITY,
                    ledStepMillis = if (state.ecoMode) ECO_LED_STEP_MILLIS else 100,
                    marqueeColsPerSecond = if (state.ecoMode) ECO_MARQUEE_COLS_PER_SECOND else 10f,
                    blinkingColon = !state.ecoMode,
                    flapPageMillis = state.flapPagePause.inWholeMilliseconds *
                            if (state.ecoMode) ECO_FLAP_PAGE_FACTOR else 1,
                ),
                colors = colors,
                style = state.boardStyle,
                status = statusLabel(
                    state,
                    secondsLeft = secondsToRefresh(state.nextRefreshAt),
                    listSecondsLeft = secondsToRefresh(state.nextListRefreshAt),
                ),
                onSettingsClick = { showSettings = true },
                onRefreshClick = state::requestRefresh,
                refreshEnabled = state.canRefreshNow,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            state,
            knowsCharging = charging != null,
            onDismiss = { showSettings = false })
    }
}

/** Modalità risparmio: 5 scatti al secondo invece di 10, una colonna di marquee per scatto. */
private const val ECO_LED_STEP_MILLIS = 200L
private const val ECO_MARQUEE_COLS_PER_SECOND = 5f

/** Modalità risparmio, tabellone a palette: le pagine dei testi lunghi girano la metà delle volte. */
private const val ECO_FLAP_PAGE_FACTOR = 2

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
        state.error != null -> "nuovo tentativo tra ${countdown(secondsLeft)}"
        listSecondsLeft != null -> "dettagli tra ${countdown(secondsLeft)}"
        else -> "prossimo tra ${countdown(secondsLeft)}"
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
private fun SettingsDialog(state: LiveBoardState, knowsCharging: Boolean, onDismiss: () -> Unit) {
    var pickStation by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impostazioni") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
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
                SwitchRow("Scorrimento automatico", state.autoScroll) { state.autoScroll = it }
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
                Text("Aspetto", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BoardStyle.entries.forEachIndexed { i, style ->
                        SegmentedButton(
                            selected = state.boardStyle == style,
                            onClick = { state.boardStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(i, BoardStyle.entries.size),
                        ) { Text(if (style == BoardStyle.Led) "LED" else "Palette") }
                    }
                }
                if (state.boardStyle == BoardStyle.SplitFlap && state.canFlapSound) {
                    SwitchRow("Suono delle palette", state.flapSound) { state.flapSound = it }
                    if (state.flapSound) {
                        VolumeSlider(
                            label = "Volume delle palette",
                            value = state.flapVolume,
                            options = LiveBoardState.FLAP_VOLUME_OPTIONS,
                            onChange = {
                                state.flapVolume = it
                                state.flapClatter?.preview()
                            },
                            hint = if (state.canAnnounce && state.announce) {
                                "Scende al ${(FlapClatter.DUCKED_VOLUME * 100).roundToInt()}% durante gli annunci"
                            } else null,
                        )
                    }
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
                Text("Risparmio energetico", style = MaterialTheme.typography.titleSmall)
                SwitchRow("Modalità risparmio", state.ecoMode) { state.ecoMode = it }
                SwitchRow("Fondo nero (schermi OLED)", state.oledPalette) { state.oledPalette = it }
                if (knowsCharging) {
                    SwitchRow("Schermo acceso solo in carica", state.screenOnOnlyWhenCharging) {
                        state.screenOnOnlyWhenCharging = it
                    }
                }
                Text("Aggiornamento", style = MaterialTheme.typography.titleSmall)
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
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

/**
 * Ora locale aggiornata a ogni cambio di minuto (il tabellone mostra solo HH:MM). Si ferma mentre
 * l'app non è visibile e al ritorno si rimette subito all'ora giusta.
 */
@Composable
fun rememberNow(): LocalDateTime {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(localNow().truncatedToMinute(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val t = localNow()
                value = t.truncatedToMinute()
                delay(60_000L - (t.second * 1_000L + t.nanosecond / 1_000_000))
            }
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
