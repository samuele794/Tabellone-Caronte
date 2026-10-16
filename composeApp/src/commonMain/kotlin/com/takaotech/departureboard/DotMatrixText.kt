package com.takaotech.departureboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
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
    /**
     * Lampeggio: se c'è, a ogni disegno dice se i LED accesi si vedono; quando ritorna false il
     * modulo mostra solo quelli spenti. Va letto al disegno, così non ricompone.
     */
    lit: (() -> Boolean)? = null,
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
    val p = with(LocalDensity.current) { pitch.toPx() }
    // Chi scorre usa una striscia lunga un periodo (testo + spazio), LED spenti compresi:
    // si sposta a colonne intere, quindi la griglia resta identica.
    val stripCols = if (scrolling) period else boxCols
    val leds = rememberLedTiles(run, startCols, stripCols, p, offColor, color) {
        val lit = List(run.size) { i ->
            Offset((startCols + run.xs[i] + 0.5f) * p, (run.ys[i] + 0.5f) * p)
        }
        ledTiles(stripCols, DotMatrixFont.ROWS, p, offColor, lit, color)
    }
    val blank = if (lit != null) {
        rememberLedTiles(boxCols, p, offColor) {
            ledTiles(boxCols, DotMatrixFont.ROWS, p, offColor, emptyList(), color)
        }
    } else {
        null
    }
    val periodPx = (period * p).roundToInt()

    Canvas(modifier.size(pitch * boxCols, pitch * DotMatrixFont.ROWS).clipToBounds()) {
        val tiles = leds.value ?: return@Canvas
        val off = blank?.value
        if (off != null && lit?.invoke() == false) {
            drawTiles(off, 0f)
        } else if (ticker != null) {
            // Scorre a scatti interi di una colonna, come i moduli LED reali.
            val col = (ticker.value * scrollSpeed / 1000).toLong() % period
            val dx = (col * p).roundToInt().toFloat()
            drawTiles(tiles, -dx)
            drawTiles(tiles, periodPx - dx)
        } else {
            drawTiles(tiles, 0f)
        }
    }
}

/** Disegna i pezzi di una striscia che parte da [x], saltando quelli fuori dal riquadro. */
private fun DrawScope.drawTiles(tiles: List<LedTile>, x: Float) {
    for (tile in tiles) {
        val left = x + tile.x
        if (left < size.width && left + tile.bitmap.width > 0) drawImage(
            tile.bitmap,
            Offset(left, 0f)
        )
    }
}
