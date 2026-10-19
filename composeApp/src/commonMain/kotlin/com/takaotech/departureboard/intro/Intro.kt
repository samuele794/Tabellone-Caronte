package com.takaotech.departureboard.intro

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.live.LiveBoardState
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.SoundPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "Intro"

/**
 * Intro d'avvio sopra il tabellone [board]: terminale della sala circolazione, cruscotto con il
 * tabellone, banner "TABELLONE ATTIVATO", poi il tabellone si allarga a tutto schermo e resta lì.
 * Con [LiveBoardState.dashboard] invece resta nel cruscotto, che rimane attorno a lui anche dopo
 * l'intro, quando la si salta o quando è spenta.
 *
 * [board] è composto sempre, dall'inizio: intanto carica stazione e treni, e alla fine è già il
 * tabellone vero. Parte quando le impostazioni sono ripristinate e solo se [LiveBoardState.intro]
 * è attivo; con [ready] = false aspetta (sul web serve un gesto prima di poter suonare). Si salta
 * solo col pulsante SKIP nella barra in alto del terminale: tocchi e tasti altrove non fanno nulla,
 * e non arrivano al tabellone sotto. Con [enabled] = false (già vista) non parte nemmeno; altrimenti
 * [onFinished] si chiama una volta: alla fine, quando la si salta, o subito se è spenta nelle
 * impostazioni.
 *
 * L'audio ([IntroTimeline.TRACK]) suona con [player]; la grafica segue l'audio da quando comincia
 * a sentirsi. Senza [player], con l'audio silenziato o nelle ore di quiete gira muta.
 */
@Composable
fun IntroHost(
    state: LiveBoardState,
    player: SoundPlayer?,
    onFinished: () -> Unit,
    enabled: Boolean = true,
    ready: Boolean = true,
    board: @Composable () -> Unit,
) {
    val colors = introColors()
    var finished by remember { mutableStateOf(!enabled) }
    var running by remember { mutableStateOf(false) }
    var skipRequested by remember { mutableStateOf(false) }
    var decoy by remember { mutableStateOf<Station?>(null) }
    val clock = remember { mutableLongStateOf(0L) }
    val exit = remember { mutableFloatStateOf(0f) }
    val currentReady by rememberUpdatedState(ready)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        if (finished) return@LaunchedEffect
        try {
            snapshotFlow { state.restored }.first { it }
            if (!state.intro) return@LaunchedEffect
            // Prima che parta: il copione non deve cambiare a metà.
            decoy = withTimeoutOrNull(DECOY_TIMEOUT) { randomDecoy(state) }
            snapshotFlow { currentReady }.first { it }
            state.introPlaying = true
            coroutineScope {
                val audioStarted = CompletableDeferred<Unit>()
                val audio = launch {
                    try {
                        playIntroAudio(player, state) { audioStarted.complete(Unit) }
                    } finally {
                        audioStarted.complete(Unit)
                    }
                }
                // Con l'app in background l'intro finisce lì: niente musica a schermo spento.
                val watcher = launch {
                    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                    lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.STARTED) }
                    DebugLog.d(TAG) { "App in background: intro interrotta" }
                    this@coroutineScope.cancel()
                }
                withTimeoutOrNull(AUDIO_START_TIMEOUT) { audioStarted.await() }
                running = true
                val t0 = withFrameNanos { it }
                var skipFrom = -1L
                while (true) {
                    val ms = (withFrameNanos { it } - t0) / 1_000_000
                    clock.longValue = ms
                    if (skipRequested && skipFrom < 0) {
                        skipFrom = ms
                        audio.cancel()
                    }
                    if (skipFrom >= 0) {
                        exit.floatValue =
                            IntroTimeline.progress(ms, skipFrom, IntroTimeline.SKIP_FADE)
                    }
                    if (ms >= IntroTimeline.TOTAL || exit.floatValue >= 1f) break
                }
                watcher.cancel()
                audio.cancel()
            }
        } finally {
            state.introPlaying = false
            finished = true
            currentOnFinished()
        }
    }

    val keepDashboard = { state.restored && state.dashboard }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().introBoardLayer(
                millis = { clock.longValue },
                exit = { exit.floatValue },
                active = { !finished },
                keepDashboard = keepDashboard,
            ),
        ) { board() }
        // Sotto l'intro solo quando sfuma perché saltata: per il resto è lei a disegnare il cruscotto.
        if (keepDashboard() && (finished || exit.floatValue > 0f)) LiveDashboard(state)
        if (!finished) {
            val info = rememberIntroInfo(state, decoy)
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                },
            ) {
                if (state.restored && state.intro) {
                    IntroOverlay(
                        millis = { clock.longValue },
                        info = info,
                        exit = { exit.floatValue },
                        onSkip = { if (running) skipRequested = true },
                        keepDashboard = keepDashboard,
                    )
                    if (!ready) TapToStart()
                } else {
                    Box(Modifier.fillMaxSize().background(colors.background))
                }
            }
        }
    }
}

/** Attesa massima dell'inizio dell'audio: poi la grafica parte lo stesso. */
private const val AUDIO_START_TIMEOUT = 3_000L

/** Attesa massima della cache stazioni: poi si prova la stazione MI 81. */
private const val DECOY_TIMEOUT = 500L

/**
 * Stazione a caso dalla cache, diversa da quella del tabellone, che il terminale prova per prima
 * senza risposta; null senza cache.
 */
private suspend fun randomDecoy(state: LiveBoardState): Station? {
    val stations = try {
        state.cachedStations()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.d(TAG) { "Cache stazioni non letta: ${e.message ?: e::class.simpleName}" }
        emptyList()
    }
    val current = state.station
    return stations.filter {
        it.id != current.id && !it.name.equals(
            current.name,
            ignoreCase = true
        )
    }
        .randomOrNull()
}

/** Suona la traccia dell'intro, se si può; [onStart] quando comincia a sentirsi. */
private suspend fun playIntroAudio(
    player: SoundPlayer?,
    state: LiveBoardState,
    onStart: () -> Unit
) {
    if (player == null || state.muted) return
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    if (BackgroundSound.isQuiet(now)) {
        DebugLog.d(TAG) { "Ore di silenzio: intro muta" }
        return
    }
    try {
        player.play(Res.readBytes(IntroTimeline.TRACK), onStart = onStart)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.d(TAG) { "Audio dell'intro non riprodotto: ${e.message ?: e::class.simpleName}" }
    }
}

/**
 * Opacità del pulsante SKIP a [millis]: compare in dissolvenza da [IntroTimeline.SKIP_ARMED_AT]
 * (prima non ci si arriva per sbaglio col gesto che avvia l'intro sul web).
 */
internal fun skipButtonAlpha(millis: Long): Float =
    IntroTimeline.progress(millis, IntroTimeline.SKIP_ARMED_AT, SKIP_BUTTON_FADE)

private const val SKIP_BUTTON_FADE = 300L

@Composable
private fun TapToStart() {
    val colors = introColors()
    val fonts = rememberIntroFonts()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val units = IntroUnits(min(maxWidth / 1600, maxHeight / 900), LocalDensity.current)
        Text(
            "TOCCA PER AVVIARE IL TERMINALE",
            style = units.mono(fonts, 26, colors.primary),
            modifier = Modifier.align(Alignment.Center)
                .background(colors.background.copy(alpha = 0.85f))
                .padding(units * 16),
        )
    }
}

/**
 * Dati del tabellone per l'intro; l'ora parte da [start] (di solito quando l'intro compare),
 * [lead] ms prima dell'inizio dell'intro.
 */
@Composable
private fun rememberIntroInfo(
    state: LiveBoardState,
    decoy: Station?,
    start: Instant = remember { Clock.System.now() },
    lead: Long = 0,
): IntroInfo {
    val local = remember(start) { start.toLocalDateTime(TimeZone.currentSystemDefault()) }
    fun flag(value: Boolean) = if (value) "TRUE" else "FALSE"
    return IntroInfo(
        station = state.station,
        decoy = decoy,
        modeLabel = if (state.mode == BoardMode.Arrivals) "ARRIVI" else "PARTENZE",
        sourceLabel = state.source.name.uppercase(),
        departures = state.departures,
        notices = state.notices,
        config = listOf(
            "ANNUNCI_VOCALI" to flag(state.announce),
            "SOTTOFONDO" to flag(state.background),
            "STILE_TABELLONE" to if (state.boardStyle == BoardStyle.Led) "LED" else "PALETTE",
            "SCORRIMENTO_AUTO" to flag(state.autoScroll),
            "MODALITA_RISPARMIO" to flag(state.ecoMode),
            "TAVOLOZZA_OLED" to flag(state.oledPalette),
            "AGGIORNAMENTO" to "${state.refreshInterval().inWholeSeconds}S",
            "FORNITORE" to state.source.name.uppercase(),
            "STAZIONE_ID" to state.station.id,
        ),
        date = local.date,
        startMillisOfDay = local.time.toMillisecondOfDay().toLong() - lead,
    )
}

/** Zoom finale (0..1, già con easing) a [millis]. */
internal fun zoomAt(millis: Long): Float = easeInOut(
    IntroTimeline.progress(
        millis,
        IntroTimeline.ZOOM_AT,
        IntroTimeline.TOTAL - IntroTimeline.ZOOM_AT
    )
)

/**
 * Il tabellone sotto l'intro: invisibile durante il terminale, poi nel riquadro centrale del
 * cruscotto, sfocato sotto il banner, e infine a tutto schermo. Con [exit] (intro saltata) va a
 * tutto schermo e compare in dissolvenza. Con [keepDashboard] non si allarga: resta nel riquadro
 * centrale, anche dopo. Con [active] = false (intro finita) non tocca nulla, salvo il cruscotto.
 */
internal fun Modifier.introBoardLayer(
    millis: () -> Long,
    exit: () -> Float,
    active: () -> Boolean,
    keepDashboard: () -> Boolean = { false },
): Modifier = graphicsLayer {
    if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
    val keep = keepDashboard()
    val running = active()
    if (!running && !keep) return@graphicsLayer
    val g = dashboardGeometry(size.width, size.height, MIN_SIDE.toPx())
    val end = if (keep) g.center else Rect(0f, 0f, size.width, size.height)
    transformOrigin = TransformOrigin(0f, 0f)
    fun place(target: Rect) {
        scaleX = target.width / size.width
        scaleY = target.height / size.height
        translationX = target.left
        translationY = target.top
    }
    if (!running) {
        place(end)
        return@graphicsLayer
    }
    val t = millis()
    val k = exit()
    place(lerp(if (keep) g.center else g.boardRect(zoomAt(t)), end, k))
    alpha = if (IntroTimeline.phaseAt(t) == IntroPhase.Terminal) k else 1f
    val blur = bannerAmount(t) * (1f - k) * BANNER_BLUR.toPx()
    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
}

/** Sfocatura dello sfondo sotto il banner. */
private val BANNER_BLUR = 10.dp

/**
 * Tutto quello che l'intro disegna sopra il tabellone a [millis]; con [exit] > 0 (intro
 * saltata) sfuma. Il pulsante SKIP chiama [onSkip]; senza, si vede ma non risponde. Senza stato:
 * serve anche per gli snapshot.
 */
@Composable
fun IntroOverlay(
    millis: () -> Long,
    info: IntroInfo,
    exit: () -> Float = { 0f },
    onSkip: (() -> Unit)? = null,
    keepDashboard: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val fonts = rememberIntroFonts()
    val scrim = MaterialTheme.colorScheme.scrim
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { alpha = 1f - exit() }) {
        val compact = maxWidth < maxHeight
        val units = rememberIntroUnits()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val geometry = rememberDashboardGeometry()
        val phase by remember { derivedStateOf { IntroTimeline.phaseAt(millis()) } }

        if (phase >= IntroPhase.Glitch) {
            DashboardScreen(
                millis, info, fonts, units, geometry,
                zoom = { if (keepDashboard()) 0f else zoomAt(millis()) },
                modifier = Modifier.graphicsLayer {
                    val blur = bannerAmount(millis()) * BANNER_BLUR.toPx()
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                },
            )
        }
        if (phase == IntroPhase.Banner || phase == IntroPhase.Zoom) {
            Box(
                Modifier.fillMaxSize()
                    .drawBehind { drawRect(scrim, alpha = 0.5f * bannerAmount(millis())) })
            IntroBanner(millis, fonts, units)
        }
        if (phase <= IntroPhase.Glitch) {
            TerminalScreen(
                millis, info, fonts, units, compact,
                IntSize(width, height),
                onSkip,
                Modifier.glitchBands(millis),
            )
        }
    }
}

/** Unità dell'intro per questo riquadro: su schermi verticali conta solo la larghezza. */
@Composable
private fun BoxWithConstraintsScope.rememberIntroUnits(): IntroUnits {
    val density = LocalDensity.current
    val u = if (maxWidth < maxHeight) maxWidth / 900 else min(maxWidth / 1600, maxHeight / 900)
    return remember(u, density) { IntroUnits(u, density) }
}

@Composable
private fun BoxWithConstraintsScope.rememberDashboardGeometry(): DashboardGeometry {
    val density = LocalDensity.current
    val width = constraints.maxWidth.toFloat()
    val height = constraints.maxHeight.toFloat()
    return remember(width, height, density) {
        dashboardGeometry(width, height, with(density) { MIN_SIDE.toPx() })
    }
}

/**
 * Il cruscotto che resta attorno al tabellone con [LiveBoardState.dashboard], con i dati e l'ora
 * veri. Il suo tempo parte dalla fine dell'intro, così le animazioni d'ingresso sono già concluse;
 * in modalità risparmio va avanti a scatti di un secondo invece che a ogni frame.
 */
@Composable
private fun LiveDashboard(state: LiveBoardState) {
    val start = remember { Clock.System.now() }
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            if (state.ecoMode) delay(1_000) else withFrameNanos { }
            elapsed.longValue = (Clock.System.now() - start).inWholeMilliseconds
        }
    }
    val info = rememberIntroInfo(state, decoy = null, start = start, lead = IntroTimeline.TOTAL)
    val fonts = rememberIntroFonts()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DashboardScreen(
            millis = { IntroTimeline.TOTAL + elapsed.longValue },
            info = info,
            fonts = fonts,
            units = rememberIntroUnits(),
            geometry = rememberDashboardGeometry(),
            zoom = { 0f },
        )
    }
}

/** Bande orizzontali dello stacco. */
private const val GLITCH_BANDS = 18

/**
 * Stacco tra terminale e cruscotto: il terminale si spezza in bande orizzontali che tremano di
 * lato e spariscono una alla volta, scoprendo il cruscotto sotto, come nel video.
 */
private fun Modifier.glitchBands(millis: () -> Long): Modifier = drawWithContent {
    val t = millis()
    if (t < IntroTimeline.GLITCH_AT) {
        drawContent()
        return@drawWithContent
    }
    val p = IntroTimeline.progress(
        t,
        IntroTimeline.GLITCH_AT,
        IntroTimeline.DASHBOARD_AT - IntroTimeline.GLITCH_AT
    )
    val weights = FloatArray(GLITCH_BANDS) { 0.35f + hash01(41, it.toLong()) }
    val total = weights.sum()
    val jitterStep = t / 40
    var top = 0f
    for (i in 0 until GLITCH_BANDS) {
        val bottom = top + size.height * weights[i] / total
        val gone = 0.1f + 0.85f * hash01(42, i.toLong())
        if (p < gone) {
            val shake =
                (hash01(43, i * 1000L + jitterStep) - 0.5f) * size.width * 0.12f * (p / gone)
            clipRect(0f, top, size.width, bottom) {
                translate(left = shake) { this@drawWithContent.drawContent() }
            }
        }
        top = bottom
    }
}
