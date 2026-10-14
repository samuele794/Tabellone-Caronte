package departureboard

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
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
)

enum class Lamps { Off, On, Blinking }

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
 * aggiornamento); con [onSettingsClick] accanto compare una rotella delle impostazioni.
 */
@Composable
fun DepartureBoard(
    departures: List<Departure>,
    modifier: Modifier = Modifier,
    footer: BoardFooter? = null,
    labels: BoardLabels = BoardLabels(),
    layout: BoardLayout = BoardLayout(),
    colors: BoardColors = BoardColors(),
    status: String? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val g = remember(layout) { BoardGeometry(layout) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val fill = layout.rows == null && constraints.hasBoundedHeight
        var pitch =
            if (constraints.hasBoundedWidth) maxWidth / g.totalWidth else LocalDotPitch.current
        if (fill) {
            val footerH = if (footer != null) U.FOOTER_GAP + g.footerH else 0f
            pitch = min(pitch, maxHeight / (U.CHROME_H + footerH + rowsHeight(U.MIN_FILL_ROWS)))
        }
        val ticker = LocalLedTicker.current ?: rememberLedTicker()
        CompositionLocalProvider(
            LocalDotPitch provides pitch,
            LocalBoardColors provides colors,
            LocalLedTicker provides ticker,
        ) {
            BoardPanel(
                departures, footer, labels, layout, g, pitch, colors, status, onSettingsClick,
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
    onSettingsClick: (() -> Unit)?,
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
        TitleRow(labels.title, status, onSettingsClick, p, colors)
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
            BoardFooterRow(footer, g, p)
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
    LaunchedEffect(hidden, m, layout) {
        if (hidden == 0 || !layout.autoScroll) return@LaunchedEffect
        scroll.autoScroll(
            bottom = hidden * m.stepPx,
            pxPerSecond = m.stepPx * layout.scrollRowsPerSecond,
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

@Composable
private fun TitleRow(
    title: BoardLabel,
    status: String?,
    onSettingsClick: (() -> Unit)?,
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
        Spacer(Modifier.weight(1f))
        if (status != null) {
            BasicText(
                status,
                Modifier.alignByBaseline(),
                style = labelStyle(p * 4.5f, FontWeight.Medium, colors.label.copy(alpha = 0.75f)),
                maxLines = 1,
            )
        }
        if (onSettingsClick != null) {
            Spacer(Modifier.width(p * 4))
            SettingsGear(onSettingsClick, p, colors, Modifier.align(Alignment.CenterVertically))
        }
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(p * U.COL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Prima colonna: logo LED + categoria e numero
        Module(g.train, p) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d?.logo != null) {
                    DotMatrixImage(
                        d.logo,
                        slotCols = layout.logoCols,
                        sampling = d.logoSampling,
                        threshold = d.logoThreshold,
                        density = layout.logoDensity,
                    )
                } else {
                    LedBlank(layout.logoCols, DotMatrixFont.ROWS)
                }
                Spacer(Modifier.width(p * U.LOGO_GAP))
                DotMatrixText(
                    d?.let { "${it.category.padEnd(2)} ${it.number}" }.orEmpty(),
                    chars = layout.trainChars,
                    marquee = Marquee.Off,
                )
            }
        }
        Module(g.destination, p) {
            DotMatrixText(d?.destination.orEmpty(), chars = layout.destinationChars)
        }
        Module(g.time, p) {
            DotMatrixText(d?.time.orEmpty(), chars = layout.timeChars, marquee = Marquee.Off)
        }
        Module(g.delay, p) {
            val delay = d?.delayMinutes?.takeIf { it > 0 }?.let { "$it'" }.orEmpty()
            DotMatrixText(
                delay,
                chars = layout.delayChars,
                align = DotAlign.End,
                marquee = Marquee.Off
            )
        }
        Module(g.info, p) {
            DotMatrixText(d?.info.orEmpty(), chars = layout.infoChars)
        }
        Module(g.platform, p) {
            DotMatrixText(
                d?.platform.orEmpty(),
                chars = layout.platformChars,
                align = DotAlign.Center,
                marquee = Marquee.Off,
            )
        }
        RowLamps(d?.lamps ?: Lamps.Off, p)
    }
}

@Composable
private fun BoardFooterRow(footer: BoardFooter, g: BoardGeometry, p: Dp) {
    // I ":" dell'orologio lampeggiano: mezzo secondo accesi, mezzo spenti.
    val ticker = LocalLedTicker.current ?: rememberLedTicker()
    val colonOn by remember(ticker) { derivedStateOf { ticker.value / 500 % 2 == 0L } }
    val clock = if (colonOn) footer.clock else footer.clock.replace(':', ' ')

    Row(horizontalArrangement = Arrangement.spacedBy(p * U.COL_GAP)) {
        Module(g.clockW, p, heightDots = g.footerH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(p * 1.2f)) {
                    repeat(3) { i ->
                        DotMatrixText(
                            footer.date.getOrNull(i).orEmpty(),
                            chars = 3,
                            pitch = p * U.DATE_PITCH,
                            align = DotAlign.Center,
                            marquee = Marquee.Off,
                        )
                    }
                }
                Spacer(Modifier.width(p * U.LOGO_GAP))
                DotMatrixText(clock, chars = 5, pitch = p * U.CLOCK_PITCH, marquee = Marquee.Off)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(p * U.ROW_GAP)) {
            repeat(2) { i ->
                Module(g.tickerW, p) {
                    DotMatrixText(footer.messages.getOrNull(i).orEmpty(), chars = g.tickerChars)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pezzi di base
// ---------------------------------------------------------------------------------------------

/** Modulo LED: rettangolo antracite che ospita una colonna della riga. */
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
            .background(LocalBoardColors.current.module, RoundedCornerShape(p * 0.8f))
            .padding(horizontal = p * U.CELL_PAD_X),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun LedBlank(cols: Int, rows: Int) {
    val p = LocalDotPitch.current
    val off = LocalBoardColors.current.ledOff
    Spacer(
        Modifier.size(p * cols, p * rows).drawWithCache {
            val bitmap = ledBitmap(cols, rows, p.toPx(), off)
            onDrawBehind { drawImage(bitmap) }
        },
    )
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
