package com.takaotech.departureboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Quali pixel dell'immagine diventano LED accesi. */
enum class DotSampling {
    /** Pixel opachi (logo su sfondo trasparente). */
    Alpha,

    /** Pixel chiari (logo bianco su fondo scuro). */
    Luminance,

    /** Pixel scuri (logo scuro su fondo bianco). */
    Darkness,
}

/**
 * Converte un'immagine qualsiasi (per esempio il logo di un'impresa ferroviaria) in una
 * matrice di LED ambra, come i marchi nella prima colonna dei tabelloni.
 *
 * I margini vuoti attorno al disegno vengono tagliati; poi l'immagine viene ridotta a [rows]
 * righe di punti mantenendo le proporzioni e centrata in uno spazio largo [slotCols] colonne,
 * così tutti i loghi occupano la stessa larghezza.
 *
 * Con [density] > 1 il logo usa una matrice più fitta nello stesso spazio (come i pannelli ad
 * alta risoluzione dei tabelloni recenti): serve per i marchi testuali molto larghi, che in
 * 7 righe di punti non si leggerebbero.
 */
@Composable
fun DotMatrixImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    slotCols: Int = 18,
    rows: Int = DotMatrixFont.ROWS,
    pitch: Dp = LocalDotPitch.current,
    color: Color = LocalBoardColors.current.led,
    offColor: Color = LocalBoardColors.current.ledOff,
    sampling: DotSampling = DotSampling.Alpha,
    /** Copertura minima (0..1) di una cella perché il suo LED si accenda. */
    threshold: Float = 0.42f,
    /** LED per punto del tabellone, in ogni direzione. */
    density: Int = 1,
) {
    val cols = slotCols * density
    val dotRows = rows * density
    val p = with(LocalDensity.current) { pitch.toPx() } / density
    // Anche il campionamento del logo avviene in background, insieme al disegno dei LED.
    val leds = rememberLedBitmap(bitmap, cols, dotRows, sampling, threshold, p, offColor, color) {
        val grid = dotify(bitmap, cols, dotRows, sampling, threshold)
        val lit = List(grid.size) { i -> Offset((grid.xs[i] + 0.5f) * p, (grid.ys[i] + 0.5f) * p) }
        ledBitmap(cols, dotRows, p, offColor, lit, color)
    }
    Spacer(
        modifier
            .size(pitch * slotCols, pitch * rows)
            .clipToBounds()
            .drawBehind { leds.value?.let { drawImage(it) } },
    )
}

/** Quanto [this] pixel conta come "pieno" (0..1) secondo [sampling]. */
internal fun Color.ink(sampling: DotSampling): Float {
    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return when (sampling) {
        DotSampling.Alpha -> alpha
        DotSampling.Luminance -> lum * alpha
        DotSampling.Darkness -> (1f - lum) * alpha
    }
}

/**
 * Riquadro del disegno, senza i margini vuoti: molti loghi (es. i GIF RFI) hanno ampi margini
 * trasparenti che, ridotti a 7 righe, schiaccerebbero il marchio in una riga sola. null se
 * l'immagine è vuota.
 */
internal fun inkBounds(pixels: PixelMap, sampling: DotSampling): IntRect? {
    var left = pixels.width
    var top = pixels.height
    var right = -1
    var bottom = -1
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            if (pixels[x, y].ink(sampling) >= 0.5f) {
                left = min(left, x); right = max(right, x)
                top = min(top, y); bottom = max(bottom, y)
            }
        }
    }
    if (right < 0) return null
    return IntRect(left, top, right + 1, bottom + 1)
}

@Immutable
private class DotGrid(val xs: IntArray, val ys: IntArray) {
    val size get() = xs.size
}

private fun dotify(
    bitmap: ImageBitmap,
    slotCols: Int,
    slotRows: Int,
    sampling: DotSampling,
    threshold: Float,
): DotGrid {
    val pixels = bitmap.toPixelMap()
    fun ink(x: Int, y: Int): Float = pixels[x, y].ink(sampling)

    val bounds = inkBounds(pixels, sampling) ?: return DotGrid(IntArray(0), IntArray(0))
    val left = bounds.left
    val top = bounds.top
    val w = bounds.width
    val h = bounds.height

    // Dimensioni in punti mantenendo le proporzioni (i punti sono quadrati).
    val aspect = w.toFloat() / h
    var cols = (slotRows * aspect).roundToInt()
    var rows = slotRows
    if (cols > slotCols) {
        cols = slotCols
        rows = max(1, (slotCols / aspect).roundToInt()).coerceAtMost(slotRows)
    }
    cols = max(1, cols)
    val x0 = (slotCols - cols) / 2
    val y0 = (slotRows - rows) / 2

    val xs = ArrayList<Int>()
    val ys = ArrayList<Int>()
    for (r in 0 until rows) {
        val py0 = r * h / rows
        val py1 = max(py0 + 1, (r + 1) * h / rows)
        for (c in 0 until cols) {
            val px0 = c * w / cols
            val px1 = max(px0 + 1, (c + 1) * w / cols)
            // Campiona al massimo ~8×8 pixel per cella.
            val stepX = max(1, (px1 - px0) / 8)
            val stepY = max(1, (py1 - py0) / 8)
            var sum = 0f
            var n = 0
            var y = py0
            while (y < min(py1, h)) {
                var x = px0
                while (x < min(px1, w)) {
                    sum += ink(left + x, top + y)
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n > 0 && sum / n >= threshold) {
                xs += x0 + c
                ys += y0 + r
            }
        }
    }
    return DotGrid(xs.toIntArray(), ys.toIntArray())
}
