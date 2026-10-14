package departureboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt

enum class DotAlign { Start, Center, End }

/** Comportamento dello scorrimento orizzontale (come la colonna "informazioni"). */
enum class Marquee {
    /** Scorre solo se il testo non entra nello spazio disponibile. */
    Auto,
    Always,
    Off,
}

/**
 * Testo LED a matrice di punti 5×7.
 *
 * La larghezza è fissa e dipende da [chars] (capacità in caratteri del modulo), così le colonne
 * restano allineate come su un tabellone vero. Se il testo è più lungo, scorre a scatti di
 * una colonna di punti alla volta.
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    chars: Int = DotMatrixFont.normalize(text).length,
    pitch: Dp = LocalDotPitch.current,
    color: Color = LocalBoardColors.current.led,
    offColor: Color = LocalBoardColors.current.ledOff,
    align: DotAlign = DotAlign.Start,
    marquee: Marquee = Marquee.Auto,
    /** Velocità di scorrimento in colonne di punti al secondo. */
    scrollSpeed: Float = 10f,
    /** Spazio vuoto, in caratteri, tra la fine del testo e la sua ripetizione. */
    scrollGapChars: Int = 4,
) {
    val run = remember(text) { DotMatrixFont.layout(text) }
    val boxCols = DotMatrixFont.widthOf(chars)
    val scrolling = when (marquee) {
        Marquee.Auto -> run.width > boxCols
        Marquee.Always -> run.width > 0
        Marquee.Off -> false
    }
    val period = max(run.width, boxCols) + scrollGapChars * DotMatrixFont.ADVANCE
    val ticker = if (scrolling) LocalLedTicker.current ?: rememberLedTicker() else null
    val startCols = when {
        scrolling -> 0
        align == DotAlign.Start -> 0
        align == DotAlign.End -> max(0, boxCols - run.width)
        else -> max(0, (boxCols - run.width) / 2)
    }

    Spacer(
        modifier
            .size(pitch * boxCols, pitch * DotMatrixFont.ROWS)
            .clipToBounds()
            .drawWithCache {
                val p = pitch.toPx()
                val lit = List(run.size) { i ->
                    Offset((startCols + run.xs[i] + 0.5f) * p, (run.ys[i] + 0.5f) * p)
                }
                // Chi scorre usa una striscia lunga un periodo (testo + spazio), LED spenti compresi:
                // si sposta a colonne intere, quindi la griglia resta identica.
                val bitmap = ledBitmap(
                    if (scrolling) period else boxCols,
                    DotMatrixFont.ROWS,
                    p,
                    offColor,
                    lit,
                    color
                )
                val periodPx = (period * p).roundToInt()
                onDrawBehind {
                    if (ticker != null) {
                        // Scorre a scatti interi di una colonna, come i moduli LED reali.
                        val col = (ticker.value * scrollSpeed / 1000).toLong() % period
                        val dx = (col * p).roundToInt().toFloat()
                        drawImage(bitmap, Offset(-dx, 0f))
                        drawImage(bitmap, Offset(periodPx - dx, 0f))
                    } else {
                        drawImage(bitmap)
                    }
                }
            },
    )
}
