package com.takaotech.departureboard.live

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.BoardColors
import com.takaotech.departureboard.BoardFooter
import com.takaotech.departureboard.BoardLabels
import com.takaotech.departureboard.BoardLayout
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.DepartureBoard
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.LocalFlapClatter
import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.StationMapSupported
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.BoardAnnouncements
import com.takaotech.departureboard.announce.PlaceNames
import com.takaotech.departureboard.cache.StationCache
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.viaggiatreno.StationPositions
import com.takaotech.departureboard.viaggiatreno.ViaggiaTrenoClient
import com.takaotech.departureboard.watch.WatchPlan
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSync
import com.takaotech.departureboard.watch.WatchedTrain
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.math.abs
import kotlin.math.ceil
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
 * riapertura dell'app. Con un [flapClatter] il tabellone a palette crepita quando gira; con un
 * [backgroundSound] suona in loop il sottofondo di stazione. Con un [watchSync] si sceglie anche il
 * treno da seguire sullo smartwatch. Con [viaggiaTreno] gli elenchi stazioni arrivano con la posizione
 * di ogni stazione, per la mappa.
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
    /** Sottofondo di stazione; null = la piattaforma non lo riproduce. */
    val backgroundSound: BackgroundSound? = null,
    /** Collegamento con lo smartwatch; null = la piattaforma non ne ha. */
    val watchSync: WatchSync? = null,
    /** Posizioni delle stazioni; null = la piattaforma non mostra la mappa. */
    private val viaggiaTreno: ViaggiaTrenoClient? = null,
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

    /**
     * Posizione della stazione mostrata. Una scelta salvata prima che ci fossero le posizioni ne è
     * senza: la si prende dall'elenco, appena caricato.
     */
    val stationPosition: LatLng?
        get() = station.position ?: stations(source).firstOrNull { it.id == station.id }?.position

    /** È aperta la mappa: il tabellone non si aggiorna. */
    var showMap by mutableStateOf(false)
        private set

    /**
     * Stazioni di tutti gli elenchi caricati che hanno una posizione, una per punto: la stessa
     * stazione RFI e FNM (Milano Centrale) è un solo segnaposto.
     */
    val mapStations: List<MapStation> by derivedStateOf {
        availableSources
            .flatMap { source ->
                stations(source).mapNotNull { s ->
                    s.position?.let {
                        Triple(
                            it,
                            s.name,
                            source
                        )
                    }
                }
            }
            .groupBy { it.first }
            .map { (position, entries) ->
                MapStation(
                    entries.first().second,
                    position,
                    entries.map { it.third }.distinct()
                )
            }
    }

    /**
     * Si apre la mappa: gli aggiornamenti si fermano e i dati del tabellone si buttano, perché al
     * ritorno sarebbero vecchi. Il primo tabellone dopo la mappa fa da nuova base per gli annunci,
     * così le variazioni accumulate nel frattempo non si annunciano in blocco.
     */
    fun openMap() {
        showMap = true
        departures = emptyList()
        notices = emptyList()
        updatedAt = null
        error = null
        loadedKey = null
        lastRefresh = null
        nextRefreshAt = null
        nextListRefreshAt = null
        announcer?.clear()
        announcements.reset()
        sources.values.forEach { it.invalidateList() }
    }

    /** Si torna al tabellone, che riparte subito con un aggiornamento. */
    fun closeMap() {
        showMap = false
    }

    /**
     * Elenchi di tutti i fornitori, per la mappa: si caricano (dalla cache, se c'è) quelli non
     * ancora caricati o ancora senza posizioni. Uno alla volta, perché condividono le posizioni.
     */
    suspend fun loadMapStations() {
        availableSources
            .filter { source -> stations(source).none { it.position != null } }
            .forEach { loadStations(it) }
    }

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
    private var backgroundState by mutableStateOf(true)
    private var backgroundVolumeState by mutableIntStateOf(DEFAULT_BACKGROUND_VOLUME)
    private var introState by mutableStateOf(true)
    private var dashboardState by mutableStateOf(false)
    private var watchTrainsState by mutableStateOf<List<WatchSelection>>(emptyList())

    init {
        backgroundSound?.volume = DEFAULT_BACKGROUND_VOLUME / 100f
    }

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
        Background, BackgroundVolume, Intro, Dashboard, Watch,
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

    /** Sottofondo di stazione in loop, mentre il tabellone è visibile. */
    var background: Boolean
        get() = backgroundState
        set(value) {
            backgroundState = value
            changed(Setting.Background)
        }

    /**
     * Volume del sottofondo, in percentuale; agganciato all'opzione più vicina di
     * [BACKGROUND_VOLUME_OPTIONS]. Vale subito, anche a sottofondo in corso.
     */
    var backgroundVolume: Int
        get() = backgroundVolumeState
        set(value) {
            applyBackgroundVolume(value)
            changed(Setting.BackgroundVolume)
        }

    private fun applyBackgroundVolume(percent: Int) {
        backgroundVolumeState = BACKGROUND_VOLUME_OPTIONS.minBy { abs(it - percent) }
        backgroundSound?.volume = backgroundVolumeState / 100f
    }

    /** Se il tabellone ha un motore per il sottofondo. */
    val canPlayBackground get() = backgroundSound != null

    /** Intro "terminale circolazione" a ogni avvio dell'app. */
    var intro: Boolean
        get() = introState
        set(value) {
            introState = value
            changed(Setting.Intro)
        }

    /**
     * Il tabellone resta nel cruscotto dell'intro (intestazione, colonne laterali, fascia della
     * prossima partenza) invece di andare a tutto schermo; vale anche senza intro.
     */
    var dashboard: Boolean
        get() = dashboardState
        set(value) {
            dashboardState = value
            changed(Setting.Dashboard)
        }

    /**
     * Treni seguiti sullo smartwatch, in ordine di osservazione ([WatchPlan]); il watch ne mostra
     * uno alla volta. Ogni cambio arriva subito al watch.
     */
    var watchTrains: List<WatchSelection>
        get() = watchTrainsState
        private set(value) {
            watchTrainsState = value
            watchSync?.trainsChanged(value)
            changed(Setting.Watch)
        }

    /**
     * Segue anche [train], al suo posto nell'ordine. Riscrivendo il numero di un treno già trovato
     * in tabellone non se ne perdono i dati.
     */
    fun followOnWatch(train: WatchSelection) {
        watchTrains = WatchPlan.added(watchTrains, train)
    }

    fun unfollowOnWatch(train: WatchSelection) {
        watchTrains = watchTrains.filterNot(train::sameTrain)
    }

    /** Treni in partenza da [station] tra cui scegliere quello per lo smartwatch. */
    suspend fun watchCandidates(source: StationSource, station: Station): List<WatchedTrain> =
        checkNotNull(watchSync) { "Smartwatch non disponibile" }.candidates(source, station)

    /**
     * Treni seguiti, scelti solo per numero, comparsi in tabellone ([seen]): se ne tengono
     * etichetta e orario, e vanno al loro posto nell'ordine, qui, nelle impostazioni salvate e
     * sul watch.
     */
    fun watchTrainsSeen(seen: List<WatchSelection>) {
        val completed = WatchPlan.withDetails(watchTrains, seen)
        if (completed != watchTrains) watchTrains = completed
    }

    private var introPlayingState by mutableStateOf(false)

    /**
     * L'intro è in corso: come con [muted], annunci, sottofondo e palette tacciono (e gli annunci
     * si scartano), così non si sovrappongono alla sua musica. Non si salva.
     */
    var introPlaying: Boolean
        get() = introPlayingState
        set(value) {
            introPlayingState = value
            if (value) announcer?.clear()
        }

    /** Il tabellone non deve suonare nulla: audio silenziato o intro in corso. */
    val soundsHeld: Boolean get() = muted || introPlaying

    private var mutedState by mutableStateOf(false)

    /**
     * Audio silenziato per il momento: annunci, sottofondo e palette tacciono senza che le loro
     * impostazioni cambino. Non si salva: alla riapertura dell'app il tabellone torna a suonare.
     * Silenziando si interrompe anche l'annuncio in corso; gli annunci che arrivano nel frattempo
     * si scartano e le righe lampeggiano subito, come con gli annunci sonori spenti.
     */
    var muted: Boolean
        get() = mutedState
        set(value) {
            mutedState = value
            if (value) announcer?.clear()
        }

    /** Se il tabellone ha almeno un suono da poter silenziare. */
    val canMute get() = canAnnounce || canPlayBackground || canFlapSound

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
     * Mentre si diffonde un annuncio abbassa il crepitio delle palette
     * ([FlapClatter.DUCKED_VOLUME]) e, a poco a poco, il sottofondo: comincia a scendere quando
     * comincia il jingle e arriva a [BackgroundSound.DUCKED_VOLUME] quando il jingle finisce; a
     * fine annuncio risale in [BackgroundSound.RESTORE_DURATION]. Gira finché non viene cancellata.
     */
    suspend fun duckSoundsDuringAnnouncements(): Unit = coroutineScope {
        val announcer = announcer ?: return@coroutineScope
        val background = backgroundSound
        if (flapClatter == null && background == null) return@coroutineScope
        if (background != null) launch {
            announcer.started.collect {
                background.duck(announcer.jingleDuration() ?: BackgroundSound.QUICK_DUCK)
            }
        }
        try {
            announcer.speaking.collect { speaking ->
                flapClatter?.ducked = speaking
                if (!speaking) background?.restore()
            }
        } finally {
            flapClatter?.ducked = false
            background?.restore()
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
        if (Setting.Background !in changedBeforeRestore) backgroundState = saved.background
        if (Setting.BackgroundVolume !in changedBeforeRestore) {
            applyBackgroundVolume(saved.backgroundVolume)
        }
        if (Setting.Intro !in changedBeforeRestore) introState = saved.intro
        if (Setting.Dashboard !in changedBeforeRestore) dashboardState = saved.dashboard
        if (Setting.Watch !in changedBeforeRestore) {
            watchTrainsState = saved.followedTrains.filter { it.source in sources }
        }
        // Il watch può aver perso i treni (app reinstallata): glieli si rimanda.
        watchSync?.trainsChanged(watchTrainsState)
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
                background = background,
                backgroundVolume = backgroundVolume,
                intro = intro,
                dashboard = dashboard,
                watchTrains = watchTrains,
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

    /** Il prossimo [refresh] è chiesto a mano: riscarica anche l'elenco dei treni. */
    private var manualRefresh = false

    /**
     * Aggiorna subito, elenco dei treni compreso: [waitForRefresh] smette di attendere e il conto
     * alla rovescia riparte.
     */
    fun requestRefresh() {
        if (!canRefreshNow) return
        manualRefresh = true
        refreshRequests.trySend(Unit)
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
            val fetch = suspend { located(client.stations()) }
            // Un elenco salvato senza nessuna posizione (da prima delle posizioni, o con
            // ViaggiaTreno giù) si riscarica; intanto resta buono come ripiego.
            val complete =
                { list: List<Station> -> viaggiaTreno == null || list.any { it.position != null } }
            val list = stationCache?.stations(source, complete, fetch) ?: fetch()
            stationLists += source to list
            announcer?.places = PlaceNames(stationLists.values.flatten().map { it.name }.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stationErrors += source to "Elenco stazioni non disponibile: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Posizioni ViaggiaTreno, scaricate al più una volta per sessione e condivise tra i fornitori. */
    private var positions: StationPositions? = null

    /**
     * [stations] con la posizione di ognuna dove è nota. Senza ViaggiaTreno, o se non risponde,
     * l'elenco resta com'è: la mappa non deve togliere il tabellone.
     */
    private suspend fun located(stations: List<Station>): List<Station> {
        val client = viaggiaTreno ?: return stations
        val known = positions ?: try {
            client.stationPositions().also { positions = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(StationCache.TAG) { "Posizioni stazioni non disponibili: ${e.message}" }
            return stations
        }
        return known.locate(stations)
    }

    /**
     * Stazioni già nella cache, senza scaricare nulla: prima quelle del fornitore attuale, se non
     * ci sono quelle degli altri. Vuoto senza cache.
     */
    suspend fun cachedStations(): List<Station> {
        val cache = stationCache ?: return emptyList()
        return cache.cached(source).ifEmpty {
            availableSources.filter { it != source }.flatMap { cache.cached(it) }
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
        if (manualRefresh) {
            manualRefresh = false
            client.invalidateList()
        }
        var cancelled = false
        try {
            val board = client.board(station, key.third)
            // Arrivato mentre si apriva la mappa: è già da buttare.
            if (showMap) return
            departures = mapper.departures(board)
            notices = board.notices
            if (pausedAt?.let { it.elapsedNow() > STALE_PAUSE } == true) announcements.reset()
            pausedAt = null
            // Il primo tabellone di una stazione fa solo da base: update() non annuncia nulla.
            val news = announcements.update(key.second, key.third, board)
            when {
                news.isEmpty() -> Unit
                announce && !soundsHeld && announcer != null -> announcer.enqueue(news)
                else -> {
                    DebugLog.d(Announcer.TAG) {
                        "Annunci ${if (soundsHeld) "silenziati" else "spenti"}: scartati ${news.joinToString { it.code }}"
                    }
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

        /** Volume del sottofondo se non se ne è scelto un altro, in percentuale. */
        const val DEFAULT_BACKGROUND_VOLUME = 50

        /** Volumi del sottofondo tra cui scegliere, in percentuale. */
        val BACKGROUND_VOLUME_OPTIONS = (10..100 step 10).toList()

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
    LaunchedEffect(state) { state.duckSoundsDuringAnnouncements() }
    LaunchedEffect(state) { state.blinkAnnouncedRows() }
    // Dopo il ripristino, che altrimenti riporterebbe la selezione salvata prima.
    LaunchedEffect(state, state.restored) {
        if (state.restored) state.watchSync?.completed?.collect(state::watchTrainsSeen)
    }
    LaunchedEffect(state.source, state.restored) {
        if (state.restored && state.stations(state.source).isEmpty()) state.loadStations()
    }
    val interval = state.refreshInterval()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.background, state.soundsHeld, state.restored, lifecycle) {
        val sound = state.backgroundSound
        if (sound == null || !state.restored || !state.background || state.soundsHeld) return@LaunchedEffect
        // Come gli aggiornamenti, il sottofondo tace mentre l'app non è visibile; di notte tace comunque.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { sound.playOutsideQuietHours() }
    }
    LaunchedEffect(
        state.source,
        state.station.id,
        state.mode,
        state.restored,
        state.showMap,
        interval,
        lifecycle
    ) {
        // Con la mappa aperta il tabellone non si aggiorna; chiudendola riparte subito.
        if (!state.restored || state.showMap) return@LaunchedEffect
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
    // Il tabellone è la radice e le pagine delle impostazioni gli si impilano sopra. Gli effetti
    // qui sopra restano attivi anche a impostazioni aperte: aggiornamenti e annunci continuano.
    val backStack = rememberNavBackStack(RouteSaving, BoardRoute)
    NavDisplay(
        backStack = backStack,
        // Mentre le pagine sfumano si vede cosa c'è sotto: lo sfondo del tema, non quello chiaro
        // della finestra.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        onBack = { backStack.pop() },
        transitionSpec = { pageTransition(forward = true) },
        popTransitionSpec = { pageTransition(forward = false) },
        predictivePopTransitionSpec = { pageTransition(forward = false) },
        entryProvider = { route ->
            when (route) {
                BoardRoute -> NavEntry(route) {
                    BoardScreen(
                        state,
                        now,
                        charging,
                        onSettingsClick = { backStack.open(SettingsPage.Main) },
                        onMapClick = { backStack.open(MapRoute) },
                    )
                }

                MapRoute -> NavEntry(route) {
                    MapScreen(state, onBack = { backStack.pop() })
                }

                is SettingsPage -> NavEntry(route) {
                    SettingsScreen(
                        state,
                        route,
                        knowsCharging = charging != null,
                        onOpen = { backStack.open(it) },
                        onBack = { backStack.pop() },
                    )
                }

                else -> error("Destinazione sconosciuta: $route")
            }
        },
    )
}

/** Il tabellone, radice della navigazione. */
@Serializable
data object BoardRoute : NavKey

/** La mappa delle stazioni a tutto schermo, sopra il tabellone. */
@Serializable
data object MapRoute : NavKey

/**
 * Per salvare il back stack (rotazione, processo chiuso dal sistema) fuori da Android vanno
 * registrate tutte le destinazioni.
 */
private val RouteSaving = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(BoardRoute::class, BoardRoute.serializer())
            subclass(MapRoute::class, MapRoute.serializer())
            subclass(SettingsPage.Main::class, SettingsPage.Main.serializer())
            subclass(SettingsPage.Audio::class, SettingsPage.Audio.serializer())
            subclass(SettingsPage.Appearance::class, SettingsPage.Appearance.serializer())
            subclass(SettingsPage.Watch::class, SettingsPage.Watch.serializer())
            subclass(SettingsPage.Refresh::class, SettingsPage.Refresh.serializer())
        }
    }
}

/** Apre [route], se non è già in cima (doppio tocco mentre la pagina entra). */
private fun NavBackStack<NavKey>.open(route: NavKey) {
    if (lastOrNull() != route) add(route)
}

/** Torna indietro, ma il tabellone resta: NavDisplay non accetta un back stack vuoto. */
private fun NavBackStack<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}

/**
 * Avanti la pagina nuova entra da destra e la vecchia scivola a sinistra, sfumando; indietro al
 * contrario. Lo spostamento è un quinto della larghezza, non tutto lo schermo.
 */
private fun pageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(PAGE_MILLIS)) { direction * it / PAGE_SLIDE } +
            fadeIn(tween(PAGE_MILLIS)))
        .togetherWith(
            slideOutHorizontally(tween(PAGE_MILLIS)) { -direction * it / PAGE_SLIDE } +
                    fadeOut(tween(PAGE_MILLIS / 2))
        )
}

private const val PAGE_MILLIS = 300
private const val PAGE_SLIDE = 5

/** Il tabellone a tutto schermo; la rotella chiama [onSettingsClick], la cartina [onMapClick]. */
@Composable
private fun BoardScreen(
    state: LiveBoardState,
    now: LocalDateTime,
    charging: Boolean?,
    onSettingsClick: () -> Unit,
    onMapClick: () -> Unit,
) {
    val colors = remember(state.oledPalette) {
        if (state.oledPalette) BoardColors.Oled else BoardColors()
    }
    val screenOn = !state.screenOnOnlyWhenCharging || charging != false
    val clatter = state.flapClatter.takeIf {
        state.boardStyle == BoardStyle.SplitFlap && state.flapSound && !state.soundsHeld
    }
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
                onSettingsClick = onSettingsClick,
                onMuteClick = if (state.canMute) ({ state.muted = !state.muted }) else null,
                muted = state.muted,
                onRefreshClick = state::requestRefresh,
                refreshEnabled = state.canRefreshNow,
                onMapClick = onMapClick.takeIf { StationMapSupported },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
        // produceState tiene il valore tra un target e l'altro: senza target va azzerato, o
        // resterebbe il conto alla rovescia di prima (es. l'elenco FNM tornando a RFI).
        if (target == null) {
            value = null
            return@produceState
        }
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
