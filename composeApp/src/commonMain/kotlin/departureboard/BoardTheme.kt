package departureboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Palette del tabellone. */
@Immutable
data class BoardColors(
    /** Fondo del pannello (nero bluastro). */
    val board: Color = Color(0xFF0D0F14),
    /** Cornice esterna. */
    val frame: Color = Color(0xFF1C1E24),
    /** Fondo dei moduli LED (grigio antracite). */
    val module: Color = Color(0xFF24262C),
    /** LED acceso (ambra). */
    val led: Color = Color(0xFFFFA51F),
    /** LED spento, appena visibile sul modulo. */
    val ledOff: Color = Color(0x16FFA51F),
    /** Etichette bianche (titolo e intestazioni). */
    val label: Color = Color(0xFFF1F1EE),
    /** Spie a destra di ogni riga, spente. */
    val lampOff: Color = Color(0xFF3A3C42),
    /** Spie accese. */
    val lampOn: Color = Color(0xFFFFE7B8),
)

/** Passo (distanza tra i centri) dei punti LED. Tutte le misure del tabellone ne derivano. */
val LocalDotPitch = compositionLocalOf<Dp> { 2.5.dp }
val LocalBoardColors = staticCompositionLocalOf { BoardColors() }

/**
 * Millisecondi trascorsi, a scatti: fa avanzare marquee e spie tutti nello stesso frame e senza
 * chiedere un frame a ogni vsync. [DepartureBoard] ne fornisce uno se non c'è già.
 */
val LocalLedTicker = staticCompositionLocalOf<State<Long>?> { null }

/** Orologio a scatti di [stepMillis] per [LocalLedTicker]. */
@Composable
fun rememberLedTicker(stepMillis: Long = 100): State<Long> {
    val millis = remember { mutableLongStateOf(0L) }
    LaunchedEffect(stepMillis) {
        while (true) {
            delay(stepMillis)
            millis.longValue += stepMillis
        }
    }
    return millis
}

/** Disegna una serie di LED accesi con alone e nucleo più chiaro. */
internal fun DrawScope.drawLeds(
    points: List<Offset>,
    pitchPx: Float,
    color: Color,
    glow: Boolean = true
) {
    if (points.isEmpty()) return
    if (glow) {
        drawPoints(
            points, PointMode.Points, color.copy(alpha = color.alpha * 0.16f),
            strokeWidth = pitchPx * 1.35f, cap = StrokeCap.Round,
        )
    }
    drawPoints(
        points,
        PointMode.Points,
        color,
        strokeWidth = pitchPx * 0.78f,
        cap = StrokeCap.Round
    )
    if (glow) {
        drawPoints(
            points, PointMode.Points, lerp(color, Color.White, 0.45f),
            strokeWidth = pitchPx * 0.32f, cap = StrokeCap.Round,
        )
    }
}

/** Griglia di LED spenti di [cols]×[rows] punti. */
internal fun ledGrid(cols: Int, rows: Int, pitchPx: Float): List<Offset> =
    ArrayList<Offset>(cols * rows).apply {
        for (r in 0 until rows) for (c in 0 until cols) {
            add(Offset((c + 0.5f) * pitchPx, (r + 0.5f) * pitchPx))
        }
    }

/**
 * Matrice di [cols]×[rows] LED spenti con sopra i LED accesi [lit] (in pixel), disegnata una volta
 * sola: ridisegnare migliaia di punti a ogni frame costa molto più di una bitmap.
 */
internal fun ledBitmap(
    cols: Int,
    rows: Int,
    pitchPx: Float,
    offColor: Color,
    lit: List<Offset> = emptyList(),
    color: Color = Color.Unspecified,
): ImageBitmap = rasterize(
    ceil(cols * pitchPx).toInt().coerceAtLeast(1),
    ceil(rows * pitchPx).toInt().coerceAtLeast(1),
) {
    drawLeds(ledGrid(cols, rows, pitchPx), pitchPx, offColor, glow = false)
    drawLeds(lit, pitchPx, color)
}

/**
 * Bitmap LED prodotta da [render] su [Dispatchers.Default]. Un tabellone pieno ha centinaia di
 * matrici: disegnate nel draw bloccherebbero il thread principale per oltre un secondo a ogni
 * nuova composizione (avvio, rotazione) o cambio di passo. Vale null finché la prima non è pronta;
 * quando cambiano le [keys] resta quella precedente fino all'arrivo della nuova.
 */
@Composable
internal fun rememberLedBitmap(vararg keys: Any?, render: () -> ImageBitmap): State<ImageBitmap?> {
    val bitmap = remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(*keys) {
        bitmap.value = withContext(Dispatchers.Default) {
            // Su Android avvia subito il caricamento della texture, fuori dal primo frame.
            render().also { it.prepareToDraw() }
        }
    }
    return bitmap
}

/** Disegna su una ImageBitmap; funziona in common code (Android, desktop, iOS, web). */
fun rasterize(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        Density(1f), LayoutDirection.Ltr, Canvas(bitmap),
        Size(width.toFloat(), height.toFloat()), block,
    )
    return bitmap
}
