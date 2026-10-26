package com.takaotech.departureboard.wear.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.takaotech.departureboard.BoardColors
import com.takaotech.departureboard.DotAlign
import com.takaotech.departureboard.DotMatrixImage
import com.takaotech.departureboard.DotMatrixText
import com.takaotech.departureboard.LocalBoardColors
import com.takaotech.departureboard.LocalLedTicker
import com.takaotech.departureboard.Marquee
import com.takaotech.departureboard.rememberLedTicker
import com.takaotech.departureboard.rfi.RfiMapper
import com.takaotech.departureboard.watch.WatchOffline
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import com.takaotech.departureboard.watch.WatchedTrain
import com.takaotech.departureboard.wear.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Caratteri dei moduli di destinazione e partenza. */
private const val MODULE_CHARS = 10

/** Caratteri del ritardo, in fondo al modulo della partenza ("220'", "CANC"). */
private const val DELAY_CHARS = 4

/**
 * Caratteri massimi di categoria e numero nella colonna "treno": oltre, i LED diventano troppo
 * piccoli e il numero scorre.
 */
private const val TRAIN_CHARS = 8

/** Colonne di punti di un modulo largo [chars] caratteri 5×7 (l'ultimo senza spazio). */
private fun dots(chars: Int) = chars * 6 - 1

/** Caratteri che stanno in un modulo largo [width] con LED di passo [pitch] (bordo compreso). */
private fun charsIn(width: Dp, pitch: Dp) = ((width / pitch - 2 + 1) / 6).toInt()

/**
 * Il monitor di binario RFI del treno seguito, con la disposizione di quello di Bologna:
 * - a sinistra il binario in grande e, sotto, l'orologio;
 * - al centro destinazione e partenza (con il ritardo in fondo), separate da una riga;
 * - a destra la colonna "treno", alta quanto le due righe: impresa sopra, numero sotto;
 * - in basso le informazioni che scorrono, larghe quanto centro e destra.
 *
 * Il monitor è circa tre volte più largo che alto: qui la griglia è più alta, perché nel cerchio
 * le scritte restino leggibili. Le misure sono frazioni del diametro, così il disegno è lo stesso
 * su ogni watch. Sopra c'è lo stato dell'aggiornamento, sotto la stazione.
 *
 * Il monitor riempie lo schermo; scorrendo (dito o corona/ghiera) sotto compare il pulsante
 * "Aggiorna". Un tocco sul monitor non fa nulla, così non si aggiorna per sbaglio.
 *
 * In [ambient] il pannello diventa nero, i LED spenti spariscono, quelli accesi si attenuano e
 * perdono l'alone, le etichette perdono il riquadro: nulla scorre o lampeggia e il ticker si ferma.
 */
@Composable
fun TrainScreen(
    snapshot: WatchSnapshot,
    nowMillis: Long,
    ambient: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenPhone: () -> Unit,
) {
    val colors = if (ambient) AmbientColors else PanelColors
    CompositionLocalProvider(
        LocalBoardColors provides colors,
        LocalLedTicker provides rememberLedTicker(running = !ambient),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.board),
            contentAlignment = Alignment.Center,
        ) {
            val geometry = Geometry(min(maxWidth, maxHeight))
            if (snapshot.selection == null) {
                NoSelection(geometry, onOpenPhone, ambient)
            } else {
                val screen = Modifier.size(maxWidth, maxHeight)
                Scrolling(ambient) { scrollToTop ->
                    Box(screen) { Monitor(snapshot, nowMillis, ambient, refreshing, geometry) }
                    RefreshButton(
                        refreshing,
                        geometry,
                        onClick = {
                            onRefresh()
                            // Si torna al monitor, dove la riga in alto dice "aggiornamento…".
                            scrollToTop()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Colonna che scorre con il dito e con la corona o la ghiera, con l'indicatore di scorrimento sul
 * bordo. In ambient torna in cima e si blocca: si vede solo il monitor. [content] riceve una
 * funzione per tornare in cima.
 */
@Composable
private fun Scrolling(
    ambient: Boolean,
    content: @Composable ColumnScope.(scrollToTop: () -> Unit) -> Unit
) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ambient) {
        if (ambient) scroll.scrollTo(0) else focus.requestFocus()
    }
    ScreenScaffold(scrollState = scroll, contentPadding = PaddingValues()) {
        Column(
            Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .verticalScroll(scroll, enabled = !ambient),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content { scope.launch { scroll.animateScrollTo(0) } }
        }
    }
}

/** Il pulsante sotto il monitor: moduli antracite e scritta ambra, come il tabellone. */
@Composable
private fun RefreshButton(refreshing: Boolean, g: Geometry, onClick: () -> Unit) {
    val colors = LocalBoardColors.current
    Button(
        onClick = onClick,
        enabled = !refreshing,
        modifier = Modifier.width(g.d * 0.6f),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.module,
            contentColor = colors.led,
            disabledContainerColor = colors.module,
            disabledContentColor = colors.led.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            stringResource(if (refreshing) R.string.refreshing else R.string.refresh),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
        )
    }
    // Spazio sotto: a fine corsa il pulsante sta verso il centro, dove il cerchio è largo.
    Spacer(Modifier.height(g.d * 0.3f))
}

/**
 * Moduli LED antracite come sui monitor veri, ma pannello nero: sull'OLED i pixel neri sono
 * spenti e non consumano.
 */
private val PanelColors = BoardColors(board = Color.Black)

private val AmbientColors = BoardColors.Oled.copy(
    led = BoardColors.Oled.led.copy(alpha = 0.7f),
    ledOff = Color.Transparent,
    lampOff = Color.Transparent,
)

/** Righe sottili tra le righe del monitor. */
private val RuleColor = Color(0xFF3C3E45)

/**
 * Misure del monitor, in frazioni del diametro [d] dello schermo.
 *
 * Il passo [pitch] dei LED è quello che fa stare [MODULE_CHARS] caratteri nella colonna centrale.
 */
private class Geometry(val d: Dp) {
    /** Un "dp" del watch di riferimento (227 dp di diametro). */
    val unit = d / 227f
    val panel = d * 0.88f
    val left = d * 0.17f
    val gap = d * 0.02f
    val right = d * 0.24f
    val middle = panel - left - right - gap * 2
    val pitch = middle / (dots(MODULE_CHARS) + 2)
    val infoPitch = pitch * 0.9f
    val labelGap = unit * 3
    val ruleGap = unit * 5

    /** Testo in "dp" del watch di riferimento, senza la scala dei caratteri di sistema. */
    @Composable
    fun sp(dp: Float): TextUnit = with(LocalDensity.current) { (unit * dp).toSp() }
}

@Composable
private fun Monitor(snapshot: WatchSnapshot, nowMillis: Long, ambient: Boolean, refreshing: Boolean, g: Geometry) {
    val selection = checkNotNull(snapshot.selection)
    val train = snapshot.train
    val live = snapshot.liveTrain()
    Box(Modifier.fillMaxSize()) {
        StatusLine(
            compactStatusText(snapshot, nowMillis),
            refreshing,
            g,
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = g.d * 0.15f),
        )
        Row(Modifier
            .align(Alignment.Center)
            .width(g.panel)
            .height(IntrinsicSize.Min)) {
            Column(
                Modifier
                    .width(g.left)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Platform(live?.platform.orEmpty(), g)
                AnalogClock(nowMillis, ambient, Modifier.size(g.left))
            }
            Spacer(Modifier.width(g.gap))
            Column {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.width(g.middle)) {
                        LabelRow(stringResource(R.string.label_destination), g) {
                            if (live?.flashing == true) Lamp(blinking = !ambient, size = g.pitch * 4)
                        }
                        Module(g) {
                            DotMatrixText(
                                train?.destination ?: selection.label.substringAfter("→ ", ""),
                                chars = MODULE_CHARS,
                                pitch = g.pitch,
                                marquee = if (ambient) Marquee.Off else Marquee.Auto,
                                glow = !ambient,
                            )
                        }
                        Rule(g)
                        LabelRow(stringResource(R.string.label_departure), g) {
                            if ((live?.delayMinutes ?: 0) > 0) Label(stringResource(R.string.label_delay), g)
                        }
                        Module(g) {
                            DotMatrixText(
                                departureLed(train?.time ?: "--:--", live),
                                chars = MODULE_CHARS,
                                pitch = g.pitch,
                                marquee = Marquee.Off,
                                glow = !ambient,
                            )
                        }
                    }
                    Spacer(Modifier.width(g.gap))
                    TrainColumn(train, selection.trainNumber, ambient, g)
                }
                Rule(g)
                LabelRow(stringResource(R.string.label_info), g)
                Module(g, pitch = g.infoPitch) {
                    DotMatrixText(
                        infoText(snapshot),
                        chars = charsIn(g.middle + g.gap + g.right, g.infoPitch),
                        pitch = g.infoPitch,
                        // Scorre solo se non entra: un testo fermo non chiede frame.
                        marquee = if (ambient) Marquee.Off else Marquee.Auto,
                        glow = !ambient,
                    )
                }
            }
        }
        BasicText(
            (train?.stationName?.takeIf { it.isNotBlank() } ?: selection.station.name).uppercase(),
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = g.d * 0.13f)
                .width(g.d * 0.6f),
            style = TextStyle(
                color = LocalBoardColors.current.label.copy(alpha = 0.6f),
                fontSize = g.sp(8f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

/** Colonna "treno": logo o nome dell'impresa in alto, categoria e numero in basso. */
@Composable
private fun TrainColumn(train: WatchedTrain?, number: String, ambient: Boolean, g: Geometry) {
    val logo = remember(train?.carrierLogoBase64) { train?.carrierLogoBase64?.let(::decodeLogo) }
    val inner = g.right - g.pitch * 2
    Column(Modifier
        .width(g.right)
        .fillMaxHeight()) {
        LabelRow(stringResource(R.string.label_train), g)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(LocalBoardColors.current.module)
                .padding(g.pitch),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (logo != null) {
                DotMatrixImage(
                    logo,
                    slotCols = RfiMapper.LOGO_COLS,
                    pitch = inner / RfiMapper.LOGO_COLS,
                    threshold = RfiMapper.LOGO_THRESHOLD,
                    density = RfiMapper.LOGO_DENSITY,
                )
            } else {
                val chars = 8
                DotMatrixText(
                    train?.carrier.orEmpty(),
                    chars = chars,
                    pitch = inner / dots(chars),
                    marquee = if (ambient) Marquee.Off else Marquee.Auto,
                    glow = !ambient,
                )
            }
            // Categoria e numero separati da uno spazio: "R 91831". Se non entrano la categoria resta
            // ferma e scorre solo il numero.
            val head = train?.category?.let { "$it " }.orEmpty()
            val tail = train?.number ?: number
            val text = head + tail
            val chars = text.length.coerceIn(1, TRAIN_CHARS)
            val pitch = minOf(g.pitch * 1.2f, inner / dots(chars))
            if (text.length <= TRAIN_CHARS || head.isEmpty()) {
                DotMatrixText(
                    text,
                    chars = chars,
                    pitch = pitch,
                    marquee = if (ambient) Marquee.Off else Marquee.Auto,
                    glow = !ambient,
                )
            } else {
                // Una colonna di punti tra i due moduli, così il passo resta quello di un testo solo.
                Row {
                    DotMatrixText(
                        head,
                        chars = head.length,
                        pitch = pitch,
                        marquee = Marquee.Off,
                        glow = !ambient,
                    )
                    Spacer(Modifier.width(pitch))
                    DotMatrixText(
                        tail,
                        chars = (TRAIN_CHARS - head.length).coerceAtLeast(1),
                        pitch = pitch,
                        marquee = if (ambient) Marquee.Off else Marquee.Auto,
                        glow = !ambient,
                    )
                }
            }
        }
    }
}

/** Binario in grande, bianco e corsivo; il resto ("ovest", "tronco") più piccolo accanto. */
@Composable
private fun Platform(platform: String, g: Geometry) {
    val number = platform.trim().takeWhile { it.isDigit() }
    val rest = platform.trim().drop(number.length).trim().lowercase()
    val color = LocalBoardColors.current.label
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(
            number.ifEmpty { platform.trim() },
            style = TextStyle(
                color = color,
                fontSize = g.sp(
                    when {
                        number.isEmpty() -> 12f
                        number.length == 1 -> 36f
                        else -> 26f
                    }
                ),
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (number.isNotEmpty() && rest.isNotEmpty()) {
            BasicText(
                rest,
                Modifier.padding(bottom = g.unit * 4),
                style = TextStyle(color = color, fontSize = g.sp(8f), fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** L'orologio della stazione: quadrante bianco, tacche e lancette nere. In ambient solo il contorno. */
@Composable
private fun AnalogClock(nowMillis: Long, ambient: Boolean, modifier: Modifier = Modifier) {
    val time = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    val minutes = time.minute
    val hours = time.hour % 12 + minutes / 60f
    val face = if (ambient) Color.Black else Color(0xFFF2F2EE)
    val ink = if (ambient) Color(0xFFB0B0B0) else Color(0xFF16171A)
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(face, r, c)
        if (ambient) drawCircle(ink, r, c, style = Stroke(r * 0.03f))
        for (i in 0 until 60) {
            val hour = i % 5 == 0
            val angle = i / 60f * 2 * PI.toFloat()
            drawLine(
                ink,
                c + polar(angle, if (hour) r * 0.7f else r * 0.8f),
                c + polar(angle, r * 0.9f),
                strokeWidth = if (hour) r * 0.07f else r * 0.025f,
            )
        }
        drawLine(ink, c, c + polar(hours / 12f * 2 * PI.toFloat(), r * 0.5f), r * 0.08f, StrokeCap.Round)
        drawLine(ink, c, c + polar(minutes / 60f * 2 * PI.toFloat(), r * 0.8f), r * 0.05f, StrokeCap.Round)
        drawCircle(ink, r * 0.07f, c)
    }
}

/** Punto a distanza [radius] dal centro, con l'angolo [angle] contato dalle 12 in senso orario. */
private fun polar(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

/** Etichetta: parola in grassetto bianco, senza riquadro, direttamente sul pannello nero. */
@Composable
private fun Label(text: String, g: Geometry) {
    BasicText(
        text,
        style = TextStyle(
            color = LocalBoardColors.current.label,
            fontSize = g.sp(10f),
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

/** Riga dell'etichetta, con qualcosa allineato a destra (la spia, "ritardo"). */
@Composable
private fun LabelRow(text: String, g: Geometry, end: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = g.labelGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(text, g)
        end()
    }
}

/** Il modulo LED: riquadro antracite con i LED spenti appena visibili. */
@Composable
private fun Module(g: Geometry, pitch: Dp = g.pitch, content: @Composable () -> Unit) {
    Box(Modifier
        .background(LocalBoardColors.current.module)
        .padding(pitch)) { content() }
}

@Composable
private fun Rule(g: Geometry) {
    Box(Modifier
        .padding(vertical = g.ruleGap)
        .fillMaxWidth()
        .height(1.dp)
        .background(RuleColor))
}

/**
 * Il treno di adesso, se è sul monitor: binario, ritardo e spia valgono solo per lui. Un treno
 * "in attesa" mostra dell'ultima volta solo orario e destinazione.
 */
internal fun WatchSnapshot.liveTrain(): WatchedTrain? = train.takeIf { status == WatchStatus.OnBoard }

/** La spia "in partenza" sopra la destinazione, come il pallino dei monitor. */
@Composable
private fun Lamp(blinking: Boolean, size: Dp) {
    val ticker = LocalLedTicker.current
    val colors = LocalBoardColors.current
    // Il ticker scatta ogni 100 ms, la spia ogni 600: si ricompone solo quando cambia.
    val on by remember(ticker, blinking) {
        derivedStateOf { !blinking || ticker == null || (ticker.value / LAMP_PHASE_MILLIS) % 2 == 0L }
    }
    Box(Modifier
        .size(size)
        .clip(CircleShape)
        .background(if (on) colors.led else colors.lampOff))
}

private const val LAMP_PHASE_MILLIS = 600L

/** Testo LED del ritardo (per il tile); null se in orario o non indicato. */
internal fun delayText(train: WatchedTrain?): String? = when {
    train == null -> null
    train.cancelled -> "CANCELLATO"
    (train.delayMinutes ?: 0) > 0 -> "${train.delayMinutes}'"
    train.delayText.isNotBlank() -> train.delayText.uppercase()
    else -> null
}

/**
 * Il modulo della partenza: orario a sinistra e ritardo in fondo, come "06:20 … 220'" sul monitor;
 * "CANC" se il treno è cancellato.
 */
internal fun departureLed(time: String, train: WatchedTrain?): String {
    val delay = when {
        train == null -> ""
        train.cancelled -> "CANC"
        (train.delayMinutes ?: 0) > 0 -> "${train.delayMinutes}'"
        else -> ""
    }.take(DELAY_CHARS)
    return time.take(MODULE_CHARS - delay.length - 1).padEnd(MODULE_CHARS - delay.length) + delay
}

/** Colonna "informazioni": fermate e note, o perché mancano. */
internal fun infoText(snapshot: WatchSnapshot): String = when (snapshot.status) {
    null -> "IN ATTESA DEI DATI"
    WatchStatus.NotOnBoard -> "IN ATTESA: IL TRENO NON È ANCORA IN TABELLONE"
    WatchStatus.Error -> "MONITOR NON DISPONIBILE"
    WatchStatus.OnBoard -> snapshot.train?.info?.takeIf { it.isNotBlank() } ?: snapshot.train?.stationName.orEmpty()
}

/**
 * Quando è stato aggiornato, sopra il monitor; "aggiornamento…" mentre aggiorna, senza rotellina:
 * un'animazione continua chiederebbe un frame a ogni vsync per tutta l'attesa del telefono.
 */
@Composable
private fun StatusLine(text: String, refreshing: Boolean, g: Geometry, modifier: Modifier) {
    BasicText(
        if (refreshing) "aggiornamento…" else text,
        modifier,
        style = TextStyle(color = Color(0xFFB0B0B0), fontSize = g.sp(9f)),
        maxLines = 1,
    )
}

/** Stato corto, per il monitor: "aggiornato 3 min fa", "12 min fa · senza rete". */
internal fun compactStatusText(snapshot: WatchSnapshot, nowMillis: Long): String {
    val reason = when (snapshot.offline) {
        WatchOffline.NoNetwork -> "senza rete"
        WatchOffline.NoInternet -> "senza internet"
        null -> null
    }
    if (snapshot.updatedAt == 0L) return reason ?: "in attesa"
    val minutes = ((nowMillis - snapshot.updatedAt) / 60_000).coerceAtLeast(0)
    val age = if (minutes == 0L) "adesso" else "$minutes min fa"
    return if (reason == null) "aggiornato $age" else "$age · $reason"
}

/**
 * Stato del tile: l'ora assoluta dell'aggiornamento ("agg. 19:12", con il motivo se offline), che
 * non invecchia e quindi non obbliga a ridisegnare il tile.
 */
internal fun tileStatusText(
    snapshot: WatchSnapshot,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val reason = when (snapshot.offline) {
        WatchOffline.NoNetwork -> "senza rete"
        WatchOffline.NoInternet -> "senza internet"
        null -> null
    }
    if (snapshot.updatedAt == 0L) return reason ?: "in attesa"
    val time = Instant.ofEpochMilli(snapshot.updatedAt).atZone(zone).format(CLOCK)
    return listOfNotNull("agg. $time", reason).joinToString(" · ")
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun NoSelection(g: Geometry, onOpenPhone: () -> Unit, ambient: Boolean) {
    Column(
        Modifier.width(g.d * 0.78f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(g.unit * 8),
    ) {
        val pitch = g.d * 0.6f / (dots(12) + 2)
        Module(g, pitch) {
            DotMatrixText(
                "NESSUN TRENO",
                chars = 12,
                pitch = pitch,
                align = DotAlign.Center,
                glow = !ambient
            )
        }
        BasicText(
            "Scegli il treno da seguire nelle impostazioni dell'app sul telefono",
            style = TextStyle(
                color = LocalBoardColors.current.label,
                fontSize = g.sp(12f),
                textAlign = TextAlign.Center,
            ),
        )
        if (!ambient) {
            Button(onClick = onOpenPhone, modifier = Modifier.fillMaxWidth(0.9f)) {
                Text("Apri sul telefono", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Logo GIF del monitor; null se non si decodifica. */
private fun decodeLogo(base64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
