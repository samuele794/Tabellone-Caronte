package com.takaotech.departureboard.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Disposizione del cruscotto su uno schermo di [width]×[height] px. Il riquadro centrale
 * ([center]) ha le proporzioni dello schermo, così il tabellone vero, rimpicciolito di
 * [boardScale], ci entra senza deformarsi; le colonne laterali prendono lo spazio che avanza e
 * spariscono ([showSides] = false) se è meno di [minSide] px.
 */
@Immutable
internal data class DashboardGeometry(
    val width: Float,
    val height: Float,
    val margin: Float,
    val side: Float,
    val header: Float,
    val band: Float,
    val center: Rect,
    val showSides: Boolean,
) {
    val boardScale: Float get() = center.width / width

    /** Riquadro del tabellone con lo zoom finale a [zoom] (0 = nel cruscotto, 1 = tutto schermo). */
    fun boardRect(zoom: Float): Rect = lerp(center, Rect(0f, 0f, width, height), zoom)
}

internal fun dashboardGeometry(width: Float, height: Float, minSide: Float): DashboardGeometry {
    val margin = width * 0.008f
    val header = height * 0.085f
    val band = height * 0.13f
    val centerHeight = height - header - band - 4 * margin
    val centerWidth = centerHeight * width / height
    val side = (width - centerWidth - 4 * margin) / 2
    val left = (width - centerWidth) / 2
    val top = 2 * margin + header
    return DashboardGeometry(
        width, height, margin, side, header, band,
        Rect(left, top, left + centerWidth, top + centerHeight),
        showSides = side >= minSide,
    )
}

/**
 * Secondo atto: il cruscotto della stazione attorno al tabellone, che sta sotto e si vede dal
 * buco centrale. Con [zoom] > 0 i pannelli escono di lato e il buco si allarga con il tabellone.
 */
@Composable
internal fun DashboardScreen(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    geometry: DashboardGeometry,
    zoom: () -> Float,
    modifier: Modifier = Modifier,
) {
    val g = geometry
    Box(modifier.fillMaxSize()) {
        // Fondo con il buco dove si vede il tabellone, e la cornice del buco.
        Canvas(Modifier.fillMaxSize()) {
            val z = zoom()
            val hole = g.boardRect(z)
            clipRect(hole.left, hole.top, hole.right, hole.bottom, ClipOp.Difference) {
                drawRect(IntroColors.DashBackground, alpha = 1f - z)
                drawGrid(units.px(40), alpha = 0.5f * (1f - z))
            }
            drawFrame(hole, units.px(1.5f), units.px(14), alpha = 1f - z)
        }
        // Intestazione sopra il tabellone.
        Placed(
            Rect(g.center.left, g.margin, g.center.right, g.margin + g.header),
            Modifier.graphicsLayer {
                val e = zoom()
                translationY = -(g.header + 2 * g.margin) * e
                alpha = 1f - e
            },
        ) { Header(millis, info, fonts, units) }
        // Fascia in basso.
        Placed(
            Rect(g.center.left, g.height - g.margin - g.band, g.center.right, g.height - g.margin),
            Modifier.graphicsLayer {
                val e = zoom()
                translationY = (g.band + 2 * g.margin) * e
                alpha = 1f - e
            },
        ) { BottomBand(millis, info, fonts, units) }
        if (g.showSides) {
            Placed(
                Rect(g.margin, g.margin, g.margin + g.side, g.height - g.margin),
                Modifier.graphicsLayer {
                    val e = zoom()
                    translationX = -(g.side + 2 * g.margin) * e
                    alpha = 1f - e
                },
            ) { LeftColumn(millis, info, fonts, units, g) }
            Placed(
                Rect(
                    g.width - g.margin - g.side,
                    g.margin,
                    g.width - g.margin,
                    g.height - g.margin
                ),
                Modifier.graphicsLayer {
                    val e = zoom()
                    translationX = (g.side + 2 * g.margin) * e
                    alpha = 1f - e
                },
            ) { RightColumn(millis, info, fonts, units, g) }
        }
    }
}

/** [content] nel riquadro [rect] (px) dello schermo. */
@Composable
private fun Placed(rect: Rect, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    with(density) {
        // Prima la posizione, poi [modifier]: un graphicsLayer con alpha < 1 disegna in un buffer
        // grande quanto il nodo, e taglierebbe il contenuto spostato dopo di lui.
        Box(
            Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(rect.width.toDp(), rect.height.toDp())
                .then(modifier),
        ) { content() }
    }
}

private fun DrawScope.drawGrid(step: Float, alpha: Float) {
    var x = step
    while (x < size.width) {
        drawLine(IntroColors.Line, Offset(x, 0f), Offset(x, size.height), 1f, alpha = alpha * 0.25f)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(IntroColors.Line, Offset(0f, y), Offset(size.width, y), 1f, alpha = alpha * 0.25f)
        y += step
    }
}

/** Cornice sottile attorno a [rect] con gli angoli marcati. */
private fun DrawScope.drawFrame(rect: Rect, stroke: Float, corner: Float, alpha: Float) {
    if (alpha <= 0f) return
    val color = IntroColors.Text
    drawRect(color, rect.topLeft, rect.size, alpha = alpha * 0.5f, style = Stroke(stroke))
    val w = stroke * 2.5f
    for ((p, dx, dy) in listOf(
        Triple(rect.topLeft, 1f, 1f), Triple(rect.topRight, -1f, 1f),
        Triple(rect.bottomLeft, 1f, -1f), Triple(rect.bottomRight, -1f, -1f),
    )) {
        drawLine(color, p, Offset(p.x + corner * dx, p.y), w, alpha = alpha)
        drawLine(color, p, Offset(p.x, p.y + corner * dy), w, alpha = alpha)
    }
}

@Composable
private fun Header(millis: () -> Long, info: IntroInfo, fonts: IntroFonts, units: IntroUnits) {
    val second by remember { derivedStateOf { millis() / 1000 } }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                info.station.name.uppercase(),
                style = units.jost(fonts, 38, IntroColors.Bar),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${info.modeLabel} / COD - ${info.station.id} / ${info.clockAt(second * 1000)}",
                style = units.mono(fonts, 14, IntroColors.TextBright),
                maxLines = 1,
            )
            Canvas(
                Modifier.width(units * 260).height(units * 7).padding(top = units * 2)
            ) { drawBarcode() }
        }
        Column(horizontalAlignment = Alignment.End) {
            val style = units.mono(fonts, 12, IntroColors.TextBright)
            Text(
                "${info.sourceLabel} / ${info.modeLabel} / ${promptName(info.station)}",
                style = style,
                maxLines = 1
            )
            Text("TABELLONE / IN LINEA / SCC", style = style, maxLines = 1)
            Text("${info.date} ${info.clockAt(second * 1000)}", style = style, maxLines = 1)
        }
        Canvas(Modifier.padding(start = units * 12).size(units * 64, units * 46)) {
            drawFsLogo(Offset.Zero, size, IntroColors.TextBright)
        }
    }
}

/** Codice a barre decorativo sotto il nome della stazione. */
private fun DrawScope.drawBarcode() {
    var x = 0f
    var i = 0L
    val unit = size.height * 0.45f
    while (x < size.width) {
        val w = unit * (1 + (hash01(31, i) * 3).toInt())
        if (i % 2 == 0L) drawRect(IntroColors.TextBright, Offset(x, 0f), Size(w, size.height))
        x += w + unit
        i++
    }
}

@Composable
private fun BottomBand(millis: () -> Long, info: IntroInfo, fonts: IntroFonts, units: IntroUnits) {
    val minute by remember { derivedStateOf { millis() / 10_000 } }
    val next = remember(info.departures, minute) { info.nextDeparture(minute * 10_000) }
    Row(
        Modifier.fillMaxSize().background(IntroColors.Tile)
            .border(units * 1.5, IntroColors.Signal.copy(alpha = 0.7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HazardBlock(units, Modifier.fillMaxHeight().aspectRatio(1.3f))
        Column(
            Modifier.weight(1f).padding(horizontal = units * 12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PROSSIMA PARTENZA", style = units.jost(fonts, 30), maxLines = 1)
            Box(
                Modifier.padding(top = units * 6).border(units * 1, IntroColors.TextDim)
                    .padding(horizontal = units * 22, vertical = units * 4),
            ) {
                val text =
                    next?.first?.let { "${it.category} ${it.number} · ${it.destination} · ${it.time}" }
                        ?: "IN ATTESA DEI DATI"
                Text(
                    text.uppercase(),
                    style = units.jost(fonts, 20, IntroColors.Signal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HazardBlock(units, Modifier.fillMaxHeight().aspectRatio(1.3f))
    }
}

/** Blocco a strisce gialle e nere, come le barriere dei passaggi a livello. */
@Composable
private fun HazardBlock(units: IntroUnits, modifier: Modifier) {
    Canvas(modifier.padding(units * 8)) {
        drawRect(Color.Black)
        drawHatch(IntroColors.Signal, size.height / 3.2f, size.height / 6.4f)
        drawRect(IntroColors.Signal, style = Stroke(units.px(1.5f)))
    }
}

@Composable
private fun LeftColumn(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    g: DashboardGeometry,
) {
    val gap = with(LocalDensity.current) { g.margin.toDp() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        // 1. Logo FS e sala.
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF3A444A), Color(0xFF1C2226))))
                .border(units * 1.5, Color(0xFF6A757C))
                .padding(units * 8),
        ) {
            DotMatrixLogo(
                Modifier.align(Alignment.TopCenter).fillMaxWidth(0.62f).fillMaxHeight(0.6f),
                rows = 26,
                green = Color(0xFFE6ECEE),
                red = IntroColors.LogoRed,
                angle = { millis() * 0.03f },
            )
            Column(
                Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "RFI · SALA CIRCOLAZIONE",
                    style = units.mono(fonts, 11, IntroColors.White),
                    maxLines = 1
                )
                Text("● ● ● ●", style = units.mono(fonts, 12, IntroColors.White), maxLines = 1)
            }
        }
        // 2. Stazione attiva.
        Box(
            Modifier.weight(1f).fillMaxWidth().background(IntroColors.Tile)
                .border(units * 1.5, IntroColors.Signal.copy(alpha = 0.8f)).padding(units * 10),
        ) {
            Canvas(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.55f).fillMaxHeight(0.42f)) {
                drawFsLogo(Offset.Zero, size, IntroColors.White)
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    "${stationInitials(info.station)} // ATTIVO",
                    style = units.jost(fonts, 24, IntroColors.Signal),
                    maxLines = 1,
                )
                Text(
                    "SCC / PIC / ACC-M",
                    style = units.mono(fonts, 10, IntroColors.White),
                    maxLines = 1
                )
                Text(
                    "${promptName(info.station)}_CMD//>CONNESSO",
                    style = units.mono(fonts, 10, IntroColors.White),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        // 3. Schema dei binari con un treno che passa.
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0C1719))
                .border(units * 1.5, IntroColors.Line).clipToBounds(),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawTracks(millis(), units) }
            Text(
                "SCHEMA BINARI · ${stationInitials(info.station)}",
                style = units.mono(fonts, 11, IntroColors.TextBright),
                maxLines = 1,
                modifier = Modifier.padding(units * 8),
            )
        }
        // 4. Orologio di stazione.
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF050708))
                .border(units * 1.5, Color(0xFF3A3F44)).padding(units * 10),
        ) {
            Canvas(Modifier.align(Alignment.Center).fillMaxHeight(0.86f).aspectRatio(1f)) {
                drawStationClock(info.millisOfDayAt(millis()))
            }
            Text(
                "ORA UFFICIALE",
                style = units.mono(fonts, 10, IntroColors.TextDim),
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** Tre binari con due comunicazioni e un treno a punti che li percorre. */
private fun DrawScope.drawTracks(millis: Long, units: IntroUnits) {
    val stroke = units.px(2)
    val ys = listOf(0.42f, 0.62f, 0.82f).map { it * size.height }
    val color = IntroColors.Line
    ys.forEach { y -> drawLine(color, Offset(0f, y), Offset(size.width, y), stroke) }
    drawLine(color, Offset(size.width * 0.25f, ys[0]), Offset(size.width * 0.4f, ys[1]), stroke)
    drawLine(color, Offset(size.width * 0.6f, ys[1]), Offset(size.width * 0.75f, ys[2]), stroke)
    // Segnali: verde sul binario del treno, rossi sugli altri.
    val r = units.px(3.5f)
    drawCircle(Color(0xFF3DDC84), r, Offset(size.width * 0.9f, ys[1] - units.px(9)))
    drawCircle(IntroColors.Error, r, Offset(size.width * 0.9f, ys[0] - units.px(9)))
    drawCircle(IntroColors.Error, r, Offset(size.width * 0.9f, ys[2] - units.px(9)))
    // Treno: 5 carrozze di punti ambra, scorre da sinistra a destra in 4 s.
    val length = size.width * 0.38f
    val head = ((millis % 4_000) / 4_000f) * (size.width + length)
    val car = length / 5
    for (i in 0 until 5) {
        val x = head - i * car
        drawRoundRect(
            Color(0xFFFFA51F),
            Offset(x - car * 0.9f, ys[1] - units.px(4)),
            Size(car * 0.8f, units.px(8)),
            androidx.compose.ui.geometry.CornerRadius(units.px(2)),
        )
    }
}

/** Orologio di stazione: quadrante bianco, lancette nere, lancetta dei secondi rossa. */
private fun DrawScope.drawStationClock(millisOfDay: Long) {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFFF4F4F0), r, c)
    drawCircle(Color(0xFF1A1A1A), r, c, style = Stroke(r * 0.04f))
    for (i in 0 until 60) {
        val a = i * 6f
        val long = i % 5 == 0
        val from = r * if (long) 0.72f else 0.84f
        rotate(a, c) {
            drawLine(
                Color(0xFF111111),
                Offset(c.x, c.y - r * 0.92f),
                Offset(c.x, c.y - from),
                if (long) r * 0.07f else r * 0.025f,
            )
        }
    }
    val seconds = millisOfDay / 1000f
    val hour = (seconds / 3600f) % 12
    val minute = (seconds / 60f) % 60
    fun hand(angle: Float, length: Float, width: Float, color: Color, tail: Float = 0.18f) {
        val rad = (angle - 90) * PI.toFloat() / 180
        val dir = Offset(cos(rad), sin(rad))
        drawLine(color, c - dir * (r * tail), c + dir * (r * length), width, StrokeCap.Butt)
    }
    hand(hour * 30, 0.55f, r * 0.1f, Color(0xFF111111))
    hand(minute * 6, 0.82f, r * 0.075f, Color(0xFF111111))
    val sec = (seconds % 60) * 6
    hand(sec, 0.62f, r * 0.025f, IntroColors.Error, tail = 0.28f)
    val rad = (sec - 90) * PI.toFloat() / 180
    drawCircle(IntroColors.Error, r * 0.08f, c + Offset(cos(rad), sin(rad)) * (r * 0.62f))
}

@Composable
private fun RightColumn(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    g: DashboardGeometry,
) {
    val gap = with(LocalDensity.current) { g.margin.toDp() }
    val second by remember { derivedStateOf { millis() / 1000 } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        // 1. Conto alla rovescia al prossimo treno.
        Column(Modifier.weight(1.15f).fillMaxWidth().border(units * 2, IntroColors.Signal)) {
            Box(
                Modifier.fillMaxWidth().background(IntroColors.Signal)
                    .padding(vertical = units * 3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "PROSSIMO TRENO TRA",
                    style = units.mono(fonts, 13, Color(0xFF2A2000)),
                    maxLines = 1
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val left = info.nextDeparture(second * 1000)?.second
                val (big, small) = if (left == null || left >= 100 * 60) {
                    "--" to ".--"
                } else {
                    (left / 60).toString().padStart(2, '0') to "." + (left % 60).toString()
                        .padStart(2, '0')
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(big, style = units.jost(fonts, 64), maxLines = 1)
                    Text(
                        small,
                        style = units.jost(fonts, 40, Color(0xFFB8BCBF)),
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = units * 6),
                    )
                }
            }
            Canvas(Modifier.fillMaxWidth().height(units * 12)) {
                drawHatch(IntroColors.Signal, size.height * 1.2f, size.height * 0.5f)
            }
        }
        // 2. Verifica dei circuiti di binario, che si accendono uno alla volta.
        val check by remember {
            derivedStateOf {
                (IntroTimeline.progress(
                    millis(),
                    IntroTimeline.DASHBOARD_AT + 400,
                    2_800
                ) * 100).toInt()
            }
        }
        Panel(Modifier.weight(1f), units, Color(0xFF3BA55C)) {
            PanelTitle("CIRCUITI DI BINARIO", fonts, units)
            Text(
                "VERIFICA $check%",
                style = units.mono(fonts, 11, IntroColors.TextDim),
                maxLines = 1
            )
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(top = units * 6),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { i ->
                    val lit = check >= (i + 1) * 25
                    Box(Modifier.weight(1f).fillMaxHeight(0.8f).padding(horizontal = units * 4)) {
                        Box(
                            Modifier.align(Alignment.Center).aspectRatio(1f)
                                .background(
                                    if (lit) Color(0xFF3BA55C).copy(alpha = 0.35f) else Color.Transparent,
                                    RoundedCornerShape(units * 6),
                                )
                                .border(
                                    units * 2,
                                    if (lit) Color(0xFF6FE08F) else Color(0xFF3A3F44),
                                    RoundedCornerShape(units * 6),
                                ),
                        )
                    }
                }
            }
        }
        // 3. Disposizioni di servizio.
        Panel(Modifier.weight(1f), units, Color(0xFF8A9096)) {
            PanelTitle("DISPOSIZIONE DI SERVIZIO", fonts, units)
            Text("IN ATTESA", style = units.mono(fonts, 11, IntroColors.TextDim), maxLines = 1)
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(top = units * 6)
                    .border(units * 1, Color(0xFF3A3F44)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "NESSUNA DISPOSIZIONE ATTIVA",
                    style = units.mono(fonts, 13, IntroColors.White),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // 4. Avvisi ai viaggiatori.
        Panel(Modifier.weight(1.1f), units, IntroColors.Bar) {
            PanelTitle("AVVISI AI VIAGGIATORI", fonts, units)
            Text(
                (info.notices.firstOrNull() ?: "NESSUN AVVISO IN CORSO").uppercase(),
                style = units.mono(fonts, 11, IntroColors.TextDim),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = units * 4),
            )
            Text(
                "◉ RFI.IT/INFOMOBILITA",
                style = units.mono(fonts, 13, IntroColors.Bar),
                maxLines = 1
            )
            Spacer(Modifier.height(units * 2))
            Text(
                "MESSAGGIO DIFFUSO DALLA SALA CIRCOLAZIONE",
                style = units.mono(fonts, 8, IntroColors.TextDim),
                maxLines = 1,
            )
        }
    }
}

/** Titolo dei pannelli di destra: su due righe se la colonna è stretta. */
@Composable
private fun PanelTitle(text: String, fonts: IntroFonts, units: IntroUnits) {
    Text(text, style = units.jost(fonts, 16), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Panel(
    modifier: Modifier,
    units: IntroUnits,
    border: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(IntroColors.Tile).border(units * 1.5, border)
            .padding(units * 10),
        content = content,
    )
}

/** Larghezza minima delle colonne laterali del cruscotto. */
internal val MIN_SIDE = 96.dp
