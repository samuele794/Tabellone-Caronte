package com.takaotech.departureboard.intro

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.PathParser
import com.takaotech.departureboard.rasterize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Logo FS (`Solo_Logo_Ferrovie_dello_Stato_Italiane.svg`, 600×415): le due frecce verdi e il
 * tratto rosso.
 */
internal object FsLogo {
    const val WIDTH = 600f
    const val HEIGHT = 415f

    val Green = Color(0xFF006564)
    val Red = Color(0xFFDC062B)

    /** Verde del marchio schiarito, per i punti accesi del logo su fondo scuro. */
    val LightGreen = Color(0xFF2E9C96)

    private const val GREEN_PATH =
        "m572.07 48.355c-162.74 1.4249-189.83 28.104-226.68 95.219l180.05 0.0822zm8.2112-19.72 " +
                "9.7274-19.025h-266.89c-90.497 0-136.67 30.808-164.75 87.894-21.967 44.646-21.903 " +
                "44.673-21.903 44.673h121.21c43.12-83.117 95.639-111.77 224.08-113.54zm-341.24 " +
                "158.11-126.96 12.933-85.583 170.78c67.681 0 102.42-6.6219 142-63.415 16.715-24.296 " +
                "46.326-72.65 70.54-120.3"

    private const val RED_PATH =
        "m402.44 280.15c-10.696 60.31-57.99 107.91-118.01 118.88 0 0-35.914 6.3205-59.488 " +
                "6.3388-23.565 0.0183-214.94 0-214.94 0l8.2934-17.007s72.796-0.56629 82.55-0.56629c25.099 " +
                "0.56629 70.567-4.9596 70.567-4.9596 51.331-5.462 91.711-39.129 104.54-88.076l14.459-49.386c7.855-40.946 " +
                "43.696-71.919 86.724-71.919l139.26-8.8597-6.6676 13.427-42.892 2.6579c-31.904 " +
                "2.6122-57.497 27.648-61.077 59.406l-3.3338 40.051z"

    val green: Path by lazy { PathParser().parsePathString(GREEN_PATH).toPath() }
    val red: Path by lazy { PathParser().parsePathString(RED_PATH).toPath() }
}

/**
 * Disegna il logo FS pieno nel riquadro [topLeft]–[size], centrato e in proporzione; con
 * [tint] tutto di quel colore, altrimenti [green] e rosso del marchio.
 */
internal fun DrawScope.drawFsLogo(
    topLeft: Offset,
    size: Size,
    tint: Color? = null,
    alpha: Float = 1f,
    green: Color = FsLogo.Green,
) {
    val s = min(size.width / FsLogo.WIDTH, size.height / FsLogo.HEIGHT)
    val dx = topLeft.x + (size.width - FsLogo.WIDTH * s) / 2
    val dy = topLeft.y + (size.height - FsLogo.HEIGHT * s) / 2
    translate(dx, dy) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(FsLogo.green, tint ?: green, alpha = alpha)
            drawPath(FsLogo.red, tint ?: FsLogo.Red, alpha = alpha)
        }
    }
}

/**
 * Logo FS come matrice di punti: [cols]×[rows] celle, con il centro (in celle) di ogni punto
 * acceso e se è del tratto rosso.
 */
@Immutable
internal class LogoDots(
    val cols: Int,
    val rows: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val red: BooleanArray,
) {
    val size get() = xs.size
}

/** Campiona il logo in [rows] righe di punti (le colonne seguono le proporzioni del logo). */
internal fun logoDots(rows: Int): LogoDots {
    val cols = (rows * FsLogo.WIDTH / FsLogo.HEIGHT).toInt()
    // ~4×4 pixel per cella: abbastanza per decidere copertura e colore.
    val cell = 4
    val bitmap = rasterize(cols * cell, rows * cell) {
        drawFsLogo(Offset.Zero, this.size)
    }
    val pixels = bitmap.toPixelMap()
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val red = ArrayList<Boolean>()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            var alpha = 0f
            var redness = 0f
            for (y in r * cell until (r + 1) * cell) {
                for (x in c * cell until (c + 1) * cell) {
                    val p = pixels[x, y]
                    alpha += p.alpha
                    redness += p.alpha * (p.red - p.green)
                }
            }
            val n = (cell * cell).toFloat()
            if (alpha / n >= 0.35f) {
                xs += c + 0.5f
                ys += r + 0.5f
                red += redness > 0f
            }
        }
    }
    return LogoDots(cols, rows, xs.toFloatArray(), ys.toFloatArray(), red.toBooleanArray())
}

/**
 * Logo FS a punti che gira sull'asse verticale come una moneta, alla maniera del globo ASCII del
 * terminale del video. [angle] (gradi) si legge al disegno, così la rotazione non ricompone; il
 * lato "dietro" è più tenue.
 */
@Composable
internal fun DotMatrixLogo(
    modifier: Modifier = Modifier,
    rows: Int = 90,
    green: Color,
    red: Color,
    alpha: Float = 1f,
    angle: () -> Float = { 0f },
) {
    val dots = remember(rows) { logoDots(rows) }
    Canvas(modifier) {
        val pitch = min(size.width / dots.cols, size.height / dots.rows)
        val cos = cos(angle() * (kotlin.math.PI / 180).toFloat())
        val cx = size.width / 2
        val ox = (size.width - dots.cols * pitch) / 2
        val oy = (size.height - dots.rows * pitch) / 2
        val greens = ArrayList<Offset>(dots.size)
        val reds = ArrayList<Offset>()
        for (i in 0 until dots.size) {
            val x = ox + dots.xs[i] * pitch
            val p = Offset(cx + (x - cx) * cos, oy + dots.ys[i] * pitch)
            if (dots.red[i]) reds += p else greens += p
        }
        val face = if (cos >= 0) 1f else 0.55f
        // Di taglio i punti si ammucchiano: si attenuano un po' per non fare una riga piena.
        val a = alpha * face * (0.45f + 0.55f * max(abs(cos), 0.15f))
        val stroke = pitch * 0.62f
        drawPoints(greens, PointMode.Points, green, stroke, StrokeCap.Round, alpha = a)
        drawPoints(reds, PointMode.Points, red, stroke, StrokeCap.Round, alpha = a)
    }
}
