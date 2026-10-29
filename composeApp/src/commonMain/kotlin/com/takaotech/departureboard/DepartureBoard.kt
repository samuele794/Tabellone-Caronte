package com.takaotech.departureboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.map_open
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Modello
// ---------------------------------------------------------------------------------------------

/** Una riga del tabellone partenze. */
@Immutable
data class Departure(
    /** Categoria del treno: "AV", "R", "RV", "IC", "EC"… */
    val category: String,
    val number: String,
    val destination: String,
    /** Orario programmato, "HH:MM". */
    val time: String,
    /** Ritardo in minuti; null o 0 = in orario. */
    val delayMinutes: Int? = null,
    /** Fermate / avvisi: scorre se più lungo della colonna. */
    val info: String = "",
    val platform: String = "",
    /**
     * Marchio dell'impresa ferroviaria. Viene convertito in LED ambra da [DotMatrixImage]:
     * va bene un PNG su fondo trasparente.
     */
    val logo: ImageBitmap? = null,
    val logoSampling: DotSampling = DotSampling.Alpha,
    /** Copertura minima di una cella del logo per accendere il LED: più bassa per tratti sottili. */
    val logoThreshold: Float = 0.42f,
    /** Le due spie a destra della riga (lampeggiano quando il treno è in partenza). */
    val lamps: Lamps = Lamps.Off,
    /** Testo della fonte al posto dei minuti ("RITARDO"): se c'è, si mostra com'è e scorre se non entra. */
    val delayText: String = "",
    /**
     * Il testo della riga lampeggia finché è true (solo a LED): segnala il treno di un annuncio.
     * Il lampeggio comincia spento, con fasi di [BLINK_PHASE_MILLIS].
     */
    val blink: Boolean = false,
)

enum class Lamps { Off, On, Blinking }

/** Durata di ogni fase (spenta o accesa) del lampeggio di una riga: multiplo dei passi del ticker. */
const val BLINK_PHASE_MILLIS = 600L

/** Etichetta bilingue: lingua principale sopra, seconda lingua sotto. */
@Immutable
data class BoardLabel(val primary: String, val secondary: String)

@Immutable
data class BoardLabels(
    val title: BoardLabel = BoardLabel("Partenze", "Departures"),
    val train: BoardLabel = BoardLabel("treno", "train"),
    val destination: BoardLabel = BoardLabel("destinazione", "destination"),
    val time: BoardLabel = BoardLabel("orario", "time"),
    val delay: BoardLabel = BoardLabel("ritardo", "delay"),
    val info: BoardLabel = BoardLabel("informazioni", "information"),
    val platform: BoardLabel = BoardLabel("binario", "platform"),
) {
    companion object {
        /** Tabellone arrivi: la colonna della stazione indica la provenienza. */
        val Arrivals = BoardLabels(
            title = BoardLabel("Arrivi", "Arrivals"),
            destination = BoardLabel("provenienza", "from"),
        )

        /** Variante altoatesina, come nella prima foto. */
        val ItalianGerman = BoardLabels(
            title = BoardLabel("Partenze", "Abfahrten"),
            train = BoardLabel("Treno", "Zug"),
            destination = BoardLabel("Destinazione", "Nach"),
            time = BoardLabel("Orario", "Zeit"),
            delay = BoardLabel("Ritardo", "Verspät."),
            info = BoardLabel("Informazioni", "Zusatzangaben"),
            platform = BoardLabel("Binario", "Gleis"),
        )
    }
}

/** Capacità delle colonne in caratteri LED (ognuno largo 6 punti), righe e scorrimento verticale. */
@Immutable
data class BoardLayout(
    /** Larghezza in punti dello spazio per il logo nella prima colonna. */
    val logoCols: Int = 18,
    /** LED del logo per ogni punto del tabellone: > 1 per marchi testuali larghi. */
    val logoDensity: Int = 1,
    /** Caratteri per categoria + numero ("AV 9915", "BUS 1247A"). */
    val trainChars: Int = 9,
    val destinationChars: Int = 14,
    val timeChars: Int = 5,
    val delayChars: Int = 4,
    val infoChars: Int = 28,
    val platformChars: Int = 3,
    /**
     * Righe visibili; null = quante ne entrano nell'altezza disponibile (il tabellone la occupa
     * tutta). Se le partenze sono di più, le righe scorrono avanti e indietro.
     */
    val rows: Int? = 10,
    /**
     * Con [rows] fisse e un'altezza limitata, il passo dei LED si riduce anche perché il
     * tabellone entri in altezza, non solo in larghezza.
     */
    val fitHeight: Boolean = false,
    /**
     * true = le righe in eccesso scorrono da sole; false = solo a mano. A mano (rotella,
     * trackpad) si può sempre scorrere: l'automatico riprende dopo [scrollResumeMillis].
     */
    val autoScroll: Boolean = true,
    /** Velocità dello scorrimento verticale, in righe al secondo. */
    val scrollRowsPerSecond: Float = 0.5f,
    /** Sosta in cima e in fondo prima di invertire lo scorrimento. */
    val scrollPauseMillis: Long = 3_000,
    /** Attesa dall'ultimo scroll manuale prima che riprenda quello automatico. */
    val scrollResumeMillis: Long = 2_000,
    /**
     * Passo del [LocalLedTicker]: ogni quanto avanzano marquee, spie e scorrimento delle righe.
     * Più lungo = meno frame al secondo, quindi meno consumo.
     */
    val ledStepMillis: Long = 100,
    /** Velocità del marquee, in colonne di punti al secondo: una colonna per scatto è la più fluida. */
    val marqueeColsPerSecond: Float = 10f,
    /** I ":" dell'orologio lampeggiano; false = sempre accesi. */
    val blinkingColon: Boolean = true,
    /**
     * Tabellone a palette: quanto resta ferma ogni pagina di un testo più lungo della colonna,
     * contando da quando la girata è finita.
     */
    val flapPageMillis: Long = 5_000,
)

/** Fascia inferiore: orologio, data e due righe di avvisi a scorrimento. */
@Immutable
data class BoardFooter(
    /** "HH:MM" */
    val clock: String,
    /** Fino a tre righe brevi, es. listOf("MER", "13", "FEB"). */
    val date: List<String> = emptyList(),
    /** Fino a due avvisi. */
    val messages: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Geometria (tutto in "punti" LED; il passo reale è scelto in base alla larghezza disponibile)
// ---------------------------------------------------------------------------------------------

private object U {
    const val FRAME = 3f
    const val PAD = 8f
    const val CELL_PAD_X = 2f
    const val CELL_PAD_Y = 2f
    const val COL_GAP = 3f
    const val ROW_GAP = 3f
    const val LOGO_GAP = 3f
    const val LAMP = 3.2f
    const val LAMP_GAP = 1.8f
    const val LAMPS_W = 2 * LAMP + LAMP_GAP + 1f
    const val CLOCK_PITCH = 2.8f
    const val DATE_PITCH = 0.8f

    /** Altezza delle palette di orologio e data, in punti del loro passo: entrano nella fascia. */
    const val CLOCK_FLAP_H = 8.4f
    const val DATE_FLAP_H = 8.8f
    const val CELL_H = DotMatrixFont.ROWS + 2 * CELL_PAD_Y
    const val ROW_STEP = CELL_H + ROW_GAP
    const val FOOTER_GAP = 6f

    /** Righe minime garantite quando il tabellone riempie l'altezza: sotto, si rimpicciolisce. */
    const val MIN_FILL_ROWS = 3

    /** Stima di cornice, titolo e intestazioni (il testo ha metriche del font, non punti esatti). */
    const val CHROME_H =
        2 * (FRAME + PAD) + 11.5f * 1.25f + 3f + 0.35f + 4f + 2 * 3.8f * 1.1f + 2.5f
}

/** Altezza in punti di [rows] righe consecutive. */
private fun rowsHeight(rows: Int) = rows * U.ROW_STEP - U.ROW_GAP

private class BoardGeometry(l: BoardLayout) {
    fun cellW(chars: Int) = DotMatrixFont.widthOf(chars) + 2 * U.CELL_PAD_X

    val train = l.logoCols + U.LOGO_GAP + cellW(l.trainChars)
    val destination = cellW(l.destinationChars)
    val time = cellW(l.timeChars)
    val delay = cellW(l.delayChars)
    val info = cellW(l.infoChars)
    val platform = cellW(l.platformChars)

    val content = train + destination + time + delay + info + platform + 6 * U.COL_GAP + U.LAMPS_W
    val totalWidth = content + 2 * (U.PAD + U.FRAME)

    // Fascia inferiore
    val footerH = 2 * U.CELL_H + U.ROW_GAP
    val dateW = DotMatrixFont.widthOf(3) * U.DATE_PITCH
    val clockW = dateW + U.LOGO_GAP + DotMatrixFont.widthOf(5) * U.CLOCK_PITCH + 2 * U.CELL_PAD_X
    val tickerW = content - U.LAMPS_W - U.COL_GAP - clockW - U.COL_GAP
    val tickerChars = ((tickerW - 2 * U.CELL_PAD_X + 1) / DotMatrixFont.ADVANCE).toInt()
}

// ---------------------------------------------------------------------------------------------
// Tabellone
// ---------------------------------------------------------------------------------------------

/**
 * Tabellone partenze in stile stazione italiana.
 *
 * Si adatta alla larghezza disponibile: il passo dei LED viene calcolato in modo che tutto il
 * pannello entri nella larghezza; l'altezza segue. Con `layout.rows = null` occupa invece tutta
 * l'altezza disponibile e mostra quante righe ci stanno.
 *
 * [status] è una scritta sulla destra della riga del titolo (es. l'ora dell'ultimo
 * aggiornamento); con [onSettingsClick] accanto compare una rotella delle impostazioni, con
 * [onMuteClick] un altoparlante per silenziare l'audio (barrato se [muted]) e con
 * [onRefreshClick] un bottone per aggiornare subito, spento finché [refreshEnabled] è falso.
 * Con [onMapClick] accanto al titolo compare una cartina per aprire la mappa delle stazioni.
 *
 * [style] sceglie come si scrive il testo: LED a matrice di punti o palette Solari, a parità di
 * colonne e misure.
 */
@Composable
fun DepartureBoard(
    departures: List<Departure>,
    modifier: Modifier = Modifier,
    footer: BoardFooter? = null,
    labels: BoardLabels = BoardLabels(),
    layout: BoardLayout = BoardLayout(),
    colors: BoardColors = BoardColors(),
    style: BoardStyle = BoardStyle.Led,
    status: String? = null,
    onSettingsClick: (() -> Unit)? = null,
    onMuteClick: (() -> Unit)? = null,
    muted: Boolean = false,
    onRefreshClick: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    onMapClick: (() -> Unit)? = null,
) {
    val g = remember(layout) { BoardGeometry(layout) }
    val glyphs = rememberFlapGlyphs()
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val fill = layout.rows == null && constraints.hasBoundedHeight
        val fitRows = layout.rows?.takeIf { layout.fitHeight && constraints.hasBoundedHeight }
        var pitch =
            if (constraints.hasBoundedWidth) maxWidth / g.totalWidth else LocalDotPitch.current
        if (fill || fitRows != null) {
            val footerH = if (footer != null) U.FOOTER_GAP + g.footerH else 0f
            val rows = fitRows ?: U.MIN_FILL_ROWS
            pitch = min(pitch, maxHeight / (U.CHROME_H + footerH + rowsHeight(rows)))
        }
        val ticker = LocalLedTicker.current ?: rememberLedTicker(layout.ledStepMillis)
        CompositionLocalProvider(
            LocalDotPitch provides pitch,
            LocalBoardColors provides colors,
            LocalLedTicker provides ticker,
            LocalBoardStyle provides style,
            LocalFlapGlyphs provides glyphs,
        ) {
            BoardPanel(
                departures, footer, labels, layout, g, pitch, colors, status,
                TitleActions(
                    onSettingsClick,
                    onMuteClick,
                    muted,
                    onRefreshClick,
                    refreshEnabled,
                    onMapClick
                ),
                modifier = if (fill) Modifier.height(maxHeight) else Modifier,
            )
        }
    }
}

@Composable
private fun BoardPanel(
    departures: List<Departure>,
    footer: BoardFooter?,
    labels: BoardLabels,
    layout: BoardLayout,
    g: BoardGeometry,
    p: Dp,
    colors: BoardColors,
    status: String?,
    actions: TitleActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(colors.frame, RoundedCornerShape(p * 5))
            .padding(p * U.FRAME)
            .background(colors.board, RoundedCornerShape(p * 3))
            .padding(p * U.PAD)
            .width(p * g.content),
    ) {
        TitleRow(labels.title, status, actions, p, colors)
        Spacer(Modifier.height(p * 3))
        Rule(colors)
        Spacer(Modifier.height(p * 4))
        HeaderRow(labels, g, p, colors)
        Spacer(Modifier.height(p * 2.5f))
        val rows = layout.rows
        if (rows != null) {
            DepartureRows(departures, rows, layout, g, p)
        } else {
            FillRows(departures, layout, g, p)
        }
        if (footer != null) {
            Spacer(Modifier.height(p * U.FOOTER_GAP))
            BoardFooterRow(footer, layout, g, p)
        }
    }
}

/** Occupa l'altezza rimasta nel pannello e ci mette quante righe intere ci stanno. */
@Composable
private fun ColumnScope.FillRows(
    departures: List<Departure>,
    layout: BoardLayout,
    g: BoardGeometry,
    p: Dp
) {
    BoxWithConstraints(Modifier.weight(1f)) {
        val visible = if (constraints.hasBoundedHeight) {
            val m = rowMetrics(p)
            ((constraints.maxHeight + m.gapPx) / m.stepPx).coerceAtLeast(1)
        } else {
            departures.size.coerceAtLeast(1)
        }
        DepartureRows(departures, visible, layout, g, p)
    }
}

/**
 * [visible] righe: se le partenze sono meno, le restanti restano spente; se sono di più, scorrono
 * lentamente fino all'ultima e tornano alla prima alla stessa velocità (se
 * [BoardLayout.autoScroll]). Si possono sempre scorrere anche a mano.
 */
@Composable
private fun DepartureRows(
    departures: List<Departure>,
    visible: Int,
    layout: BoardLayout,
    g: BoardGeometry,
    p: Dp,
) {
    val scroll = rememberScrollState()
    val hidden = (departures.size - visible).coerceAtLeast(0)
    val m = rowMetrics(p)
    val user = remember { UserScrollTracker() }
    val ticker = LocalLedTicker.current ?: rememberLedTicker(layout.ledStepMillis)
    val dotPx = with(LocalDensity.current) { p.toPx() }
    LaunchedEffect(hidden, m, layout, ticker, dotPx) {
        if (hidden == 0 || !layout.autoScroll) return@LaunchedEffect
        scroll.autoScroll(
            bottom = hidden * m.stepPx,
            pxPerSecond = m.stepPx * layout.scrollRowsPerSecond,
            dotPx = dotPx,
            ticker = ticker,
            pauseMillis = layout.scrollPauseMillis,
            resumeMillis = layout.scrollResumeMillis,
            user = user,
        )
    }
    Column(
        Modifier
            .height(with(LocalDensity.current) { m.heightPx(visible).toDp() })
            .nestedScroll(user)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(p * U.ROW_GAP),
    ) {
        repeat(maxOf(departures.size, visible)) { i ->
            DepartureRow(departures.getOrNull(i), layout, g, p)
        }
    }
}

/**
 * Altezza di riga e spazio tra righe in pixel interi, come li arrotonda il layout: con le misure
 * frazionarie in dp l'errore si accumula riga dopo riga e l'ultima finirebbe tagliata.
 */
private data class RowMetrics(val rowPx: Int, val gapPx: Int) {
    val stepPx get() = rowPx + gapPx
    fun heightPx(rows: Int) = rows * stepPx - gapPx
}

@Composable
private fun rowMetrics(p: Dp): RowMetrics = with(LocalDensity.current) {
    RowMetrics((p * U.CELL_H).roundToPx(), (p * U.ROW_GAP).roundToPx())
}

/** Bottoni a destra della riga del titolo; quelli null non compaiono. */
private class TitleActions(
    val onSettingsClick: (() -> Unit)?,
    val onMuteClick: (() -> Unit)?,
    val muted: Boolean,
    val onRefreshClick: (() -> Unit)?,
    val refreshEnabled: Boolean,
    val onMapClick: (() -> Unit)?,
)

@Composable
private fun TitleRow(
    title: BoardLabel,
    status: String?,
    actions: TitleActions,
    p: Dp,
    colors: BoardColors,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BasicText(
            title.primary,
            Modifier.alignByBaseline(),
            style = labelStyle(p * 11.5f, FontWeight.Bold, colors.label),
        )
        Spacer(Modifier.width(p * 6))
        BasicText(
            title.secondary,
            Modifier.alignByBaseline(),
            style = labelStyle(p * 8f, FontWeight.Medium, colors.label),
        )
        actions.onMapClick?.let { onClick ->
            Spacer(Modifier.width(p * 4))
            MapButton(onClick, p, colors, Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.weight(1f))
        if (status != null) {
            BasicText(
                status,
                Modifier.alignByBaseline(),
                style = labelStyle(p * 4.5f, FontWeight.Medium, colors.label.copy(alpha = 0.75f)),
                maxLines = 1,
            )
        }
        actions.onRefreshClick?.let { onClick ->
            Spacer(Modifier.width(p * 4))
            RefreshButton(
                onClick,
                actions.refreshEnabled,
                p,
                colors,
                Modifier.align(Alignment.CenterVertically)
            )
        }
        actions.onMuteClick?.let { onClick ->
            Spacer(Modifier.width(p * if (actions.onRefreshClick != null) 1 else 4))
            MuteButton(
                onClick,
                actions.muted,
                p,
                colors,
                Modifier.align(Alignment.CenterVertically)
            )
        }
        actions.onSettingsClick?.let { onClick ->
            val first = actions.onRefreshClick == null && actions.onMuteClick == null
            Spacer(Modifier.width(p * if (first) 4 else 1))
            SettingsGear(onClick, p, colors, Modifier.align(Alignment.CenterVertically))
        }
    }
}

/** Cartina piegata in tre per aprire la mappa delle stazioni, col colore delle etichette. */
@Composable
private fun MapButton(
    onClick: () -> Unit,
    p: Dp,
    colors: BoardColors,
    modifier: Modifier = Modifier
) {
    val description = stringResource(Res.string.map_open)
    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = description }
            .padding(p * 1.5f),
    ) {
        Spacer(
            Modifier.size(p * 8).drawWithCache {
                val s = size.minDimension
                val stroke = Stroke(s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val map = foldedMapPath(s)
                onDrawBehind {
                    drawPath(map, colors.label, style = stroke)
                    // Pieghe tra i tre pannelli.
                    drawLine(
                        colors.label,
                        Offset(s * 0.36f, s * 0.12f),
                        Offset(s * 0.36f, s * 0.78f),
                        stroke.width
                    )
                    drawLine(
                        colors.label,
                        Offset(s * 0.64f, s * 0.22f),
                        Offset(s * 0.64f, s * 0.88f),
                        stroke.width
                    )
                }
            },
        )
    }
}

/** Contorno di una cartina piegata a zig-zag in tre pannelli, in un quadrato di lato [s]. */
private fun foldedMapPath(s: Float): Path = Path().apply {
    moveTo(s * 0.08f, s * 0.22f)
    lineTo(s * 0.36f, s * 0.12f)
    lineTo(s * 0.64f, s * 0.22f)
    lineTo(s * 0.92f, s * 0.12f)
    lineTo(s * 0.92f, s * 0.78f)
    lineTo(s * 0.64f, s * 0.88f)
    lineTo(s * 0.36f, s * 0.78f)
    lineTo(s * 0.08f, s * 0.88f)
    close()
}

/**
 * Altoparlante per silenziare l'audio, col colore delle etichette: con le onde finché suona,
 * con una croce quando è silenziato ([muted]).
 */
@Composable
private fun MuteButton(
    onClick: () -> Unit,
    muted: Boolean,
    p: Dp,
    colors: BoardColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = if (muted) "Riattiva l'audio" else "Silenzia l'audio"
            }
            .padding(p * 1.5f),
    ) {
        Spacer(
            Modifier.size(p * 8).drawWithCache {
                val s = size.minDimension
                val speaker = speakerPath(s)
                val stroke = Stroke(s * 0.09f, cap = StrokeCap.Round)
                onDrawBehind {
                    drawPath(speaker, colors.label)
                    if (muted) {
                        val c = Offset(s * 0.80f, s * 0.5f)
                        val d = s * 0.13f
                        listOf(Offset(d, d), Offset(d, -d)).forEach { o ->
                            drawLine(colors.label, c - o, c + o, stroke.width, StrokeCap.Round)
                        }
                    } else {
                        // Due onde concentriche, centrate sulla bocca dell'altoparlante.
                        listOf(0.17f, 0.33f).forEach { radius ->
                            val r = s * radius
                            drawArc(
                                colors.label,
                                startAngle = -45f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(s * 0.52f - r, s * 0.5f - r),
                                size = Size(r * 2, r * 2),
                                style = stroke,
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Corpo e cono dell'altoparlante, rivolto a destra, in un quadrato di lato [s]. */
private fun speakerPath(s: Float): Path = Path().apply {
    moveTo(s * 0.06f, s * 0.36f)
    lineTo(s * 0.26f, s * 0.36f)
    lineTo(s * 0.50f, s * 0.14f)
    lineTo(s * 0.50f, s * 0.86f)
    lineTo(s * 0.26f, s * 0.64f)
    lineTo(s * 0.06f, s * 0.64f)
    close()
}

/**
 * Freccia circolare per aggiornare subito, col colore delle etichette; spenta ([enabled] falso)
 * è sbiadita e non si preme.
 */
@Composable
private fun RefreshButton(
    onClick: () -> Unit,
    enabled: Boolean,
    p: Dp,
    colors: BoardColors,
    modifier: Modifier = Modifier
) {
    val tint = if (enabled) colors.label else colors.label.copy(alpha = DISABLED_ALPHA)
    Box(
        modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .semantics { contentDescription = "Aggiorna" }
            .padding(p * 1.5f),
    ) {
        Spacer(
            Modifier.size(p * 8).drawWithCache {
                val r = size.minDimension / 2
                val stroke = r * 0.26f
                // Raggio dell'arco: lascia spazio alla punta della freccia, più larga del tratto.
                val arc = r - stroke * 1.1f
                val head = refreshHeadPath(r, arc, stroke)
                onDrawBehind {
                    drawArc(
                        tint,
                        startAngle = REFRESH_START,
                        sweepAngle = REFRESH_SWEEP,
                        useCenter = false,
                        topLeft = Offset(r - arc, r - arc),
                        size = Size(arc * 2, arc * 2),
                        style = Stroke(stroke),
                    )
                    drawPath(head, tint)
                }
            },
        )
    }
}

/** Arco della freccia di aggiornamento, in gradi in senso orario: lascia un varco in alto a destra. */
private const val REFRESH_START = -30f
private const val REFRESH_SWEEP = 280f

/** Opacità di un bottone spento. */
private const val DISABLED_ALPHA = 0.3f

/** Punta triangolare in fondo all'arco di raggio [arc] centrato in (r, r), rivolta in senso orario. */
private fun refreshHeadPath(r: Float, arc: Float, stroke: Float): Path {
    val a = (REFRESH_START + REFRESH_SWEEP) * PI / 180
    val radial = Offset(cos(a).toFloat(), sin(a).toFloat())
    val tangent = Offset(-radial.y, radial.x)
    val end = Offset(r, r) + radial * arc
    return Path().apply {
        val base = radial * (stroke * 1.1f)
        moveTo((end + base).x, (end + base).y)
        lineTo((end - base).x, (end - base).y)
        val tip = end + tangent * (stroke * 1.4f)
        lineTo(tip.x, tip.y)
        close()
    }
}

/** Rotella delle impostazioni, disegnata col colore delle etichette. */
@Composable
private fun SettingsGear(
    onClick: () -> Unit,
    p: Dp,
    colors: BoardColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = "Impostazioni" }
            .padding(p * 1.5f),
    ) {
        Spacer(
            Modifier.size(p * 8).drawWithCache {
                val gear = gearPath(size.minDimension / 2)
                onDrawBehind { drawPath(gear, colors.label) }
            },
        )
    }
}

/** Ingranaggio a 8 denti con foro centrale, centrato in (r, r). */
private fun gearPath(r: Float, teeth: Int = 8): Path {
    val inner = r * 0.74f
    val step = 2 * PI / teeth
    fun point(angle: Double, radius: Float) =
        Offset(r + radius * cos(angle).toFloat(), r + radius * sin(angle).toFloat())

    return Path().apply {
        fillType = PathFillType.EvenOdd
        for (i in 0 until teeth) {
            val a = i * step
            // Dente trapezoidale: base larga sul cerchio interno, cima più stretta.
            listOf(
                point(a - step * 0.27, inner),
                point(a - step * 0.16, r),
                point(a + step * 0.16, r),
                point(a + step * 0.27, inner),
            ).forEachIndexed { j, o ->
                if (i == 0 && j == 0) moveTo(o.x, o.y) else lineTo(
                    o.x,
                    o.y
                )
            }
        }
        close()
        addOval(Rect(Offset(r, r), r * 0.32f))
    }
}

@Composable
private fun Rule(colors: BoardColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(LocalDotPitch.current * 0.35f)
            .background(colors.label.copy(alpha = 0.28f)),
    )
}

@Composable
private fun HeaderRow(labels: BoardLabels, g: BoardGeometry, p: Dp, colors: BoardColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(p * U.COL_GAP)) {
        HeaderLabel(labels.train, g.train, p, colors)
        HeaderLabel(labels.destination, g.destination, p, colors)
        HeaderLabel(labels.time, g.time, p, colors)
        HeaderLabel(labels.delay, g.delay, p, colors)
        HeaderLabel(labels.info, g.info, p, colors)
        HeaderLabel(labels.platform, g.platform + U.COL_GAP + U.LAMPS_W, p, colors)
    }
}

@Composable
private fun HeaderLabel(label: BoardLabel, widthDots: Float, p: Dp, colors: BoardColors) {
    val style = labelStyle(p * 3.8f, FontWeight.SemiBold, colors.label, lineHeight = 1.1f)
    Column(Modifier.width(p * widthDots).padding(start = p * 0.5f)) {
        BasicText(label.primary, style = style, maxLines = 1)
        BasicText(label.secondary, style = style.copy(fontWeight = FontWeight.Normal), maxLines = 1)
    }
}

@Composable
private fun DepartureRow(d: Departure?, layout: BoardLayout, g: BoardGeometry, p: Dp) {
    val lit =
        if (d?.blink == true && LocalBoardStyle.current == BoardStyle.Led) rememberBlink() else null
    Row(
        horizontalArrangement = Arrangement.spacedBy(p * U.COL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Prima colonna: logo + categoria e numero
        Module(g.train, p) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoardLogo(d, layout)
                Spacer(Modifier.width(p * U.LOGO_GAP))
                BoardText(
                    d?.let { "${it.category.padEnd(2)} ${it.number}" }.orEmpty(),
                    chars = layout.trainChars,
                    layout = layout,
                    lit = lit,
                    marquee = Marquee.Off,
                )
            }
        }
        Module(g.destination, p) {
            BoardText(
                d?.destination.orEmpty(),
                chars = layout.destinationChars,
                layout = layout,
                lit = lit,
            )
        }
        Module(g.time, p) {
            BoardText(
                d?.time.orEmpty(),
                chars = layout.timeChars,
                layout = layout,
                lit = lit,
                marquee = Marquee.Off
            )
        }
        Module(g.delay, p) {
            val text = d?.delayText.orEmpty()
            val delay =
                text.ifEmpty { d?.delayMinutes?.takeIf { it > 0 }?.let { "$it'" }.orEmpty() }
            BoardText(
                delay,
                chars = layout.delayChars,
                layout = layout,
                lit = lit,
                align = DotAlign.End,
                marquee = if (text.isEmpty()) Marquee.Off else Marquee.Auto,
            )
        }
        Module(g.info, p) {
            BoardText(d?.info.orEmpty(), chars = layout.infoChars, layout = layout, lit = lit)
        }
        Module(g.platform, p) {
            BoardText(
                d?.platform.orEmpty(),
                chars = layout.platformChars,
                layout = layout,
                lit = lit,
                align = DotAlign.Center,
                marquee = Marquee.Off,
            )
        }
        RowLamps(d?.lamps ?: Lamps.Off, p)
    }
}

@Composable
private fun BoardFooterRow(footer: BoardFooter, layout: BoardLayout, g: BoardGeometry, p: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(p * U.COL_GAP)) {
        Module(g.clockW, p, heightDots = g.footerH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(p * 1.2f)) {
                    repeat(3) { i ->
                        BoardText(
                            footer.date.getOrNull(i).orEmpty(),
                            chars = 3,
                            layout = layout,
                            pitch = p * U.DATE_PITCH,
                            align = DotAlign.Center,
                            marquee = Marquee.Off,
                            flapHeight = U.DATE_FLAP_H,
                        )
                    }
                }
                Spacer(Modifier.width(p * U.LOGO_GAP))
                if (LocalBoardStyle.current == BoardStyle.SplitFlap) {
                    // I ":" stanno su una palette che non gira mai: niente lampeggio.
                    SplitFlapText(
                        footer.clock,
                        chars = 5,
                        pitch = p * U.CLOCK_PITCH,
                        heightDots = U.CLOCK_FLAP_H,
                    )
                } else {
                    LedClock(footer.clock, blink = layout.blinkingColon, pitch = p * U.CLOCK_PITCH)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(p * U.ROW_GAP)) {
            repeat(2) { i ->
                Module(g.tickerW, p) {
                    BoardText(
                        footer.messages.getOrNull(i).orEmpty(),
                        chars = g.tickerChars,
                        layout = layout
                    )
                }
            }
        }
    }
}

/**
 * Orologio LED con i ":" che lampeggiano ([blink]): mezzo secondo accesi, mezzo spenti. Le due
 * versioni si disegnano una volta sola e si alternano con la trasparenza del livello: cambiare
 * il testo a ogni lampeggio ricomporrebbe e ridisegnerebbe la matrice due volte al secondo.
 */
@Composable
private fun LedClock(clock: String, blink: Boolean, pitch: Dp) {
    if (!blink) {
        DotMatrixText(clock, chars = 5, pitch = pitch, marquee = Marquee.Off)
        return
    }
    val ticker = LocalLedTicker.current ?: rememberLedTicker()

    // Una sola versione visibile alla volta: sovrapposte, i LED spenti risulterebbero più chiari.
    fun Modifier.visibleWhen(colonOn: Boolean) =
        graphicsLayer { alpha = if ((ticker.value / 500 % 2 == 0L) == colonOn) 1f else 0f }
    Box {
        DotMatrixText(
            clock.replace(':', ' '),
            Modifier.visibleWhen(colonOn = false),
            chars = 5,
            pitch = pitch,
            marquee = Marquee.Off,
        )
        DotMatrixText(
            clock,
            Modifier.visibleWhen(colonOn = true),
            chars = 5,
            pitch = pitch,
            marquee = Marquee.Off,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pezzi di base
// ---------------------------------------------------------------------------------------------

/** Modulo: rettangolo antracite (o alloggiamento delle palette) che ospita una colonna della riga. */
@Composable
private fun Module(
    widthDots: Float,
    p: Dp,
    heightDots: Float = U.CELL_H,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(p * widthDots, p * heightDots)
            .background(
                LocalBoardColors.current.let {
                    if (LocalBoardStyle.current == BoardStyle.SplitFlap) it.flapHousing else it.module
                },
                RoundedCornerShape(p * 0.8f),
            )
            .padding(horizontal = p * U.CELL_PAD_X),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/**
 * Testo di una colonna, a LED o a palette secondo [LocalBoardStyle]. Dove i LED scorrerebbero
 * ([marquee] diverso da [Marquee.Off]) le palette mostrano il testo a pagine.
 */
@Composable
private fun BoardText(
    text: String,
    chars: Int,
    layout: BoardLayout,
    pitch: Dp = LocalDotPitch.current,
    align: DotAlign = DotAlign.Start,
    marquee: Marquee = Marquee.Auto,
    /** Altezza delle palette, in punti di [pitch]. */
    flapHeight: Float = Flap.HEIGHT,
    /** Lampeggio dei LED (vedi [DotMatrixText]); le palette lo ignorano. */
    lit: (() -> Boolean)? = null,
) {
    if (LocalBoardStyle.current == BoardStyle.SplitFlap) {
        SplitFlapText(
            text,
            chars = chars,
            pitch = pitch,
            heightDots = flapHeight,
            align = align,
            paged = marquee != Marquee.Off,
            pageMillis = layout.flapPageMillis,
        )
    } else {
        DotMatrixText(
            text,
            chars = chars,
            pitch = pitch,
            align = align,
            marquee = marquee,
            scrollSpeed = layout.marqueeColsPerSecond,
            lit = lit,
        )
    }
}

/** Marchio dell'impresa nella prima colonna: LED, o stampato su una palette. */
@Composable
private fun BoardLogo(d: Departure?, layout: BoardLayout) {
    when {
        LocalBoardStyle.current == BoardStyle.SplitFlap ->
            FlapLogo(d?.logo, layout.logoCols, sampling = d?.logoSampling ?: DotSampling.Alpha)

        d?.logo != null -> DotMatrixImage(
            d.logo,
            slotCols = layout.logoCols,
            sampling = d.logoSampling,
            threshold = d.logoThreshold,
            density = layout.logoDensity,
        )

        else -> LedBlank(layout.logoCols, DotMatrixFont.ROWS)
    }
}

@Composable
private fun LedBlank(cols: Int, rows: Int) {
    val p = LocalDotPitch.current
    val px = with(LocalDensity.current) { p.toPx() }
    val off = LocalBoardColors.current.ledOff
    val leds = rememberLedBitmap(cols, rows, px, off) { ledBitmap(cols, rows, px, off) }
    Canvas(Modifier.size(p * cols, p * rows).clipToBounds()) {
        leds.value?.let { drawImage(it) }
    }
}

/**
 * Fase del lampeggio di una riga ([Departure.blink]), contata dal primo frame: false = LED spenti.
 * Il ticker si legge solo al disegno, così la riga non si ricompone a ogni scatto.
 */
@Composable
private fun rememberBlink(): () -> Boolean {
    val ticker = LocalLedTicker.current ?: rememberLedTicker()
    val start = remember { Snapshot.withoutReadObservation { ticker.value } }
    return remember(ticker, start) { { (ticker.value - start) / BLINK_PHASE_MILLIS % 2 == 1L } }
}

/** Le due spie rotonde a destra di ogni riga. Lampeggiano alternate. */
@Composable
private fun RowLamps(state: Lamps, p: Dp) {
    val colors = LocalBoardColors.current
    val ticker =
        if (state == Lamps.Blinking) LocalLedTicker.current ?: rememberLedTicker() else null

    Canvas(Modifier.size(p * U.LAMPS_W, p * U.CELL_H)) {
        val r = U.LAMP * p.toPx() / 2
        val cy = size.height / 2
        val x1 = size.width - r - U.LAMP_GAP * p.toPx() - 2 * r
        val x2 = size.width - r
        // Ciclo di 1,1 s: mezzo periodo per spia.
        val step = ticker?.let { (it.value / 550 % 2).toInt() } ?: 0
        fun lamp(i: Int, cx: Float) {
            val on = when (state) {
                Lamps.On -> true
                Lamps.Off -> false
                Lamps.Blinking -> step == i
            }
            val c = Offset(cx, cy)
            if (on) {
                drawCircle(colors.lampOn.copy(alpha = 0.22f), r * 1.9f, c)
                drawCircle(colors.lampOn, r, c)
            } else {
                drawCircle(colors.lampOff, r, c)
                drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.55f, c.copy(y = cy + r * 0.2f))
            }
        }
        lamp(0, x1)
        lamp(1, x2)
    }
}

@Composable
private fun labelStyle(
    size: Dp,
    weight: FontWeight,
    color: Color,
    lineHeight: Float? = null,
): TextStyle {
    val fontSize: TextUnit = with(LocalDensity.current) { size.toSp() }
    return TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = weight,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 0.01.em,
        lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    )
}
