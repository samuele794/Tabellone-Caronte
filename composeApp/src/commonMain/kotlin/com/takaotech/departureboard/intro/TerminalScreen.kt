package com.takaotech.departureboard.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

/**
 * Primo atto dell'intro: il terminale della sala circolazione. Barra in alto, console a sinistra,
 * registro a destra, pannelli SISTEMA e CONFIG in basso, logo FS a punti che gira sullo sfondo.
 * Con [compact] (schermi verticali) restano solo console e SISTEMA.
 */
@Composable
internal fun TerminalScreen(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    compact: Boolean,
    sizePx: IntSize,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(IntroColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(millis, fonts, units, compact, onSkip)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Il logo sta tra la barra in alto e i pannelli in basso, dietro la console.
                DotMatrixLogo(
                    Modifier.align(Alignment.Center).fillMaxHeight(0.86f)
                        .aspectRatio(FsLogo.WIDTH / FsLogo.HEIGHT),
                    rows = 64,
                    green = IntroColors.LogoGreen,
                    red = IntroColors.LogoRed,
                    alpha = 0.3f,
                    angle = { millis() * LOGO_DEGREES_PER_SECOND / 1000f },
                )
                Row(Modifier.fillMaxSize().padding(units * 10)) {
                    Console(millis, info, fonts, units, Modifier.weight(1f).fillMaxHeight())
                    if (!compact) {
                        Spacer(Modifier.width(units * 12))
                        LogPanel(
                            millis,
                            info,
                            fonts,
                            units,
                            Modifier.width(units * 330).fillMaxHeight()
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(units * 185).padding(horizontal = units * 10),
            ) {
                SystemPanel(millis, fonts, units, compact, Modifier.weight(1f).fillMaxHeight())
                if (!compact) {
                    Spacer(Modifier.width(units * 12))
                    ConfigPanel(info, fonts, units, Modifier.width(units * 330).fillMaxHeight())
                }
            }
            Footer(millis, info, fonts, units, sizePx)
        }
    }
}

/** Gradi al secondo del logo sullo sfondo: un giro ogni 4 s, come il globo del video. */
private const val LOGO_DEGREES_PER_SECOND = 90f

@Composable
private fun TopBar(
    millis: () -> Long,
    fonts: IntroFonts,
    units: IntroUnits,
    compact: Boolean,
    onSkip: (() -> Unit)?,
) {
    val style = units.mono(fonts, 15, IntroColors.HeaderText)
    // Mai più bassa di così: ci sta SKIP, che col dito si deve poter prendere.
    Box(
        Modifier.fillMaxWidth().height(maxOf(units * 28, TOP_BAR_MIN))
            .background(IntroColors.Header)
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.width(units * 14).fillMaxHeight()) {
                drawHatch(
                    IntroColors.Background,
                    6f
                )
            }
            Box(
                Modifier.padding(horizontal = units * 6).border(units * 1, IntroColors.HeaderText)
                    .padding(horizontal = units * 8),
            ) { Text("VISTA ▾", style = style, maxLines = 1) }
            // In verticale finirebbe sotto il titolo al centro.
            if (!compact) {
                Box(
                    Modifier.width(units * 1.5).height(units * 22)
                        .background(IntroColors.HeaderText)
                )
                Text(
                    "CONNESSIONE_STABILITA//>STDOUT>registro.txt",
                    style = style,
                    maxLines = 1,
                    modifier = Modifier.padding(start = units * 12),
                )
            }
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            SmallLogo(units, IntroColors.HeaderText)
            Text(
                "TERMINALE CIRCOLAZIONE",
                style = style,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = units * 8),
            )
            SmallLogo(units, IntroColors.HeaderText)
        }
        Row(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("v1839.10.03-RFI-SCC", style = style, maxLines = 1)
            Box(
                Modifier.padding(start = units * 12).width(units * 1.5).height(units * 22)
                    .background(IntroColors.HeaderText)
            )
            SkipButton(millis, style, units, onSkip)
        }
    }
}

/** Altezza minima della barra in alto. */
private val TOP_BAR_MIN = 36.dp

/**
 * SKIP in fondo alla barra in alto, riquadrato come "VISTA": si inverte (fondo chiaro, testo
 * verde) sotto il puntatore o il dito. Si prende tutta l'altezza della barra fino al bordo dello
 * schermo; compare dopo un attimo ([skipButtonAlpha]) e senza [onSkip] non risponde.
 */
@Composable
private fun SkipButton(
    millis: () -> Long,
    style: TextStyle,
    units: IntroUnits,
    onSkip: (() -> Unit)?
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val armed by remember { derivedStateOf { skipButtonAlpha(millis()) >= 1f } }
    val active = (hovered || pressed) && armed && onSkip != null
    val content = if (active) IntroColors.Header else IntroColors.HeaderText
    Box(
        Modifier.fillMaxHeight()
            .graphicsLayer { alpha = skipButtonAlpha(millis()) }
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = armed && onSkip != null,
                role = Role.Button,
                onClickLabel = "Salta l'intro",
            ) { onSkip?.invoke() }
            .padding(start = units * 6, end = units * 12),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.background(if (active) IntroColors.HeaderText else Color.Transparent)
                .border(units * 1, IntroColors.HeaderText)
                .padding(horizontal = units * 8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SKIP", style = style.copy(color = content), maxLines = 1)
            Canvas(Modifier.padding(start = units * 6).size(units * 14, units * 9)) {
                val w = size.width / 2
                for (i in 0..1) {
                    drawPath(
                        Path().apply {
                            moveTo(i * w, 0f)
                            lineTo(i * w + w, size.height / 2)
                            lineTo(i * w, size.height)
                            close()
                        },
                        content,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallLogo(units: IntroUnits, tint: Color) {
    Canvas(Modifier.size(units * 24, units * 17)) { drawFsLogo(Offset.Zero, size, tint) }
}

@Composable
private fun Console(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    modifier: Modifier,
) {
    val script = remember(info.station, info.decoy) { consoleScript(info.station, info.decoy) }
    val lines by remember(script) { derivedStateOf { consoleAt(millis(), script) } }
    val cursorOn by remember { derivedStateOf { millis() / 450 % 2 == 0L } }
    val textSize = 19
    val lineHeight = units.px(textSize * 1.25f)
    val gapHeight = units.px(textSize * 0.75f)
    BoxWithConstraints(modifier.clipToBounds().padding(start = units * 14, top = units * 8)) {
        // Le righe più recenti che ci stanno: la console scorre come un terminale vero.
        val available = constraints.maxHeight.toFloat()
        val visible = remember(lines, available) {
            var used = 0f
            var from = lines.size
            while (from > 0) {
                val line = lines[from - 1]
                val h = lineHeight + if (line.gap) gapHeight else 0f
                if (used + h > available) break
                used += h
                from--
            }
            lines.subList(from, lines.size)
        }
        Column(
            Modifier.drawBehind {
                drawLine(
                    IntroColors.Line,
                    Offset(-units.px(10), 0f),
                    Offset(-units.px(10), size.height),
                    strokeWidth = units.px(2),
                )
            },
        ) {
            visible.forEachIndexed { i, line ->
                if (line.gap && i > 0) Spacer(Modifier.height(units * (textSize * 0.75f)))
                val color = when (line.kind) {
                    LineKind.Command -> IntroColors.TextBright
                    LineKind.Output -> IntroColors.Text
                    LineKind.Error -> IntroColors.ErrorBright
                    LineKind.Net -> IntroColors.Text
                }
                val cursor = i == visible.lastIndex && line.kind == LineKind.Command && cursorOn
                Text(
                    if (cursor) line.text + "▌" else line.text,
                    style = units.mono(fonts, textSize, color),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Una riga del registro ogni [LOG_STEP] ms. */
private const val LOG_STEP = 140L

private val LOG_KINDS = listOf(
    "[SIS] AGG. CDB" to 40,
    "[SIS] AGG. DEVIATOIO" to 24,
    "[SIS] AGG. SEGNALE" to 60,
    "[SIS] AGG. TENSIONE" to 0,
    "[UTE] AGG. COMANDO" to 0,
    "[SIS] AGG. PL" to 9,
    "[SIS] AGG. ITINERARIO" to 18,
)

/** Testo della riga [index] del registro, senza l'ora. */
internal fun logLine(index: Long): String {
    val (label, max) = LOG_KINDS[(hash01(11, index) * LOG_KINDS.size).toInt()
        .coerceAtMost(LOG_KINDS.lastIndex)]
    return if (max > 0) "$label ${1 + (hash01(12, index) * max).toInt()}" else label
}

@Composable
private fun LogPanel(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    modifier: Modifier,
) {
    val step by remember { derivedStateOf { millis() / LOG_STEP } }
    val style = units.mono(fonts, 12.5f, IntroColors.Text)
    PanelFrame("REGISTRO", fonts, units, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().padding(units * 6)) {
            val lineHeight = units.px(12.5f * 1.25f)
            val count = max(1, floor(constraints.maxHeight / lineHeight).toInt())
            Column {
                for (k in 0 until count) {
                    val index = step - k
                    if (index < 0) break
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "[${info.clockAt(index * LOG_STEP)}] ${logLine(index)}",
                            style = style,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.weight(1f),
                        )
                        Text("OK (200)", style = style, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Riquadro con l'etichetta piena in alto a sinistra, come LOGS / SYSTEM / CONFIG del video. */
@Composable
internal fun PanelFrame(
    title: String,
    fonts: IntroFonts,
    units: IntroUnits,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier.background(IntroColors.Header)
                .padding(horizontal = units * 6, vertical = units * 1)
        ) {
            Text(title, style = units.mono(fonts, 15, IntroColors.HeaderText), maxLines = 1)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(IntroColors.Panel.copy(alpha = 0.55f))
                .border(units * 1.5, IntroColors.Line),
        ) { content() }
    }
}

/** Valori delle barre di SISTEMA (0..1) a [millis]. */
internal fun systemValues(millis: Long): List<Float> {
    val t = millis / 650f
    return listOf(
        0.12f + 0.8f * noise(1, t),
        0.5f + 0.47f * noise(2, t * 0.7f),
        0.80f,
        0.1f + 0.15f * noise(4, t * 1.3f),
    )
}

private val SYSTEM_LABELS = listOf("CPU", "RAM", "ARCHIVIO", "LINEA 3 kV")

@Composable
private fun SystemPanel(
    millis: () -> Long,
    fonts: IntroFonts,
    units: IntroUnits,
    compact: Boolean,
    modifier: Modifier,
) {
    val percents by remember {
        derivedStateOf { systemValues(millis()).map { (it * 100).toInt() } }
    }
    val style = units.mono(fonts, 17, IntroColors.Text)
    PanelFrame("SISTEMA", fonts, units, modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = units * 10, vertical = units * 8)) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                SYSTEM_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            style = style,
                            maxLines = 1,
                            modifier = Modifier.width(units * 115)
                        )
                        Canvas(Modifier.weight(1f).height(units * 16)) {
                            val value = systemValues(millis())[i]
                            drawHatch(IntroColors.BarTrack, units.px(5))
                            drawRect(IntroColors.Bar, size = Size(size.width * value, size.height))
                        }
                        Text(
                            "${percents[i]}%",
                            style = style,
                            maxLines = 1,
                            modifier = Modifier.width(units * 56).padding(start = units * 8),
                        )
                    }
                }
            }
            if (!compact) {
                Spacer(Modifier.width(units * 18))
                Column(Modifier.weight(0.8f).fillMaxHeight()) {
                    Text("TRAFFICO IN TEMPO REALE", style = style, maxLines = 1)
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(top = units * 4)
                            .border(units * 1.5, IntroColors.Line).padding(units * 6),
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawTraffic(millis()) }
                    }
                }
            }
        }
    }
}

/** Istogramma che scorre a sinistra di una colonna ogni 110 ms. */
private fun DrawScope.drawTraffic(millis: Long) {
    val columns = 56
    val step = millis / 110
    val w = size.width / columns
    for (i in 0 until columns) {
        val k = step - (columns - 1 - i)
        val spike = if (hash01(21, k) > 0.82f) 0.35f else 0f
        val v = (0.2f + 0.55f * noise(22, k * 0.45f) + spike).coerceAtMost(1f)
        val h = size.height * v
        drawRect(IntroColors.Bar, Offset(i * w, size.height - h), Size(w * 0.86f, h))
    }
}

/** Righe diagonali di [color] a passo [spacing], come i tratteggi dei pannelli del video. */
internal fun DrawScope.drawHatch(color: Color, spacing: Float, stroke: Float = spacing * 0.45f) {
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color,
                Offset(x, size.height),
                Offset(x + size.height, 0f),
                strokeWidth = stroke
            )
            x += spacing
        }
    }
}

@Composable
private fun ConfigPanel(info: IntroInfo, fonts: IntroFonts, units: IntroUnits, modifier: Modifier) {
    val style = units.mono(fonts, 12.5f, IntroColors.Text)
    PanelFrame("CONFIG", fonts, units, modifier) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(units * 8)) {
            info.config.forEach { (key, value) ->
                Text(
                    "$key...>$value",
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun Footer(
    millis: () -> Long,
    info: IntroInfo,
    fonts: IntroFonts,
    units: IntroUnits,
    sizePx: IntSize
) {
    val step by remember { derivedStateOf { millis() / 250 } }
    val style: TextStyle = units.mono(fonts, 12.5f, IntroColors.Text)
    val date = info.date.toString().replace("-", "")
    Box(Modifier.fillMaxWidth().height(units * 24).padding(horizontal = units * 10)) {
        Text(
            "scc.bin[*128]:18391003190507011${date}",
            style = style,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        val cpu = (systemValues(step * 250)[0] * 100).toInt()
        Text(
            "$date${
                info.clockAt(0).replace(":", "")
            }[128] ${step % 1051}/1051 [+] MAIUSC NUM SCORR PAUSA " +
                    "${sizePx.width}x${sizePx.height} (3,0 kV) ${cpu}%",
            style = style,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}
