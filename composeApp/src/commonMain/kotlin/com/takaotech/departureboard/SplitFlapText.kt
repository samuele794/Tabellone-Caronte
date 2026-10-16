package com.takaotech.departureboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Misure e tempi delle palette; le lunghezze sono in punti del tabellone. */
internal object Flap {
    /** Altezza di una palette di testo: quasi tutto il modulo, alto 11 punti. */
    const val HEIGHT = 10f

    /** Spazio tra due palette affiancate (il passo resta quello di un carattere LED). */
    const val GAP = 0.6f

    const val CORNER = 0.45f

    /** Corpo del carattere rispetto all'altezza della palette. */
    const val FONT = 0.98f

    /** Altezza delle maiuscole rispetto al corpo, per centrarle sulla cerniera. */
    const val CAP_HEIGHT = 0.72f

    /** Durata di una girata. */
    const val FLIP_MILLIS = 55L

    /** Ritardo massimo con cui parte ogni palette: non girano tutte all'unisono. */
    const val STAGGER_MILLIS = 250

    /** Larghezza di [chars] palette affiancate. */
    fun width(chars: Int) = if (chars <= 0) 0f else chars * DotMatrixFont.ADVANCE - GAP
}

// ---------------------------------------------------------------------------------------------
// Testo
// ---------------------------------------------------------------------------------------------

/**
 * Testo su [chars] palette Solari affiancate, larghe quanto un carattere LED.
 *
 * Quando il testo cambia, ogni palette gira in avanti sul suo tamburo ([FlapDrum]) fino al
 * carattere nuovo, con un piccolo ritardo casuale; ogni girata finisce nel [LocalFlapClatter].
 * Alla prima composizione il testo compare fermo. Con [paged] un testo più lungo si divide in
 * pagine ([flapPages]): ognuna resta ferma [pageMillis] dal momento in cui l'ultima palette si è
 * posata, poi si gira alla successiva. Senza [paged] il testo si tronca.
 */
@Composable
internal fun SplitFlapText(
    text: String,
    chars: Int,
    modifier: Modifier = Modifier,
    pitch: Dp = LocalDotPitch.current,
    heightDots: Float = Flap.HEIGHT,
    align: DotAlign = DotAlign.Start,
    paged: Boolean = false,
    pageMillis: Long = 5_000,
) {
    val lines = remember(text, chars, paged, align) {
        val pages =
            if (paged) flapPages(text, chars) else listOf(DotMatrixFont.normalize(text).take(chars))
        pages.map { alignedLine(it, chars, align) }
    }
    val cells = remember(chars) { FlapCells(lines[0]) }
    val frame = remember { mutableLongStateOf(0L) }
    // Accendere o spegnere il suono non deve far ripartire le pagine dalla prima.
    val clatter by rememberUpdatedState(LocalFlapClatter.current)
    // L'attesa tra le pagine segue il ticker del tabellone: si ferma con l'app in background
    // e negli snapshot segue il tempo della scena.
    val ticker = if (lines.size > 1) LocalLedTicker.current ?: rememberLedTicker() else null
    val onScreen = remember { OnScreen() }
    LaunchedEffect(lines, cells, pageMillis, ticker) {
        var page = 0
        while (true) {
            // Ritorna quando l'ultima palette si è posata (subito, se la pagina è già quella).
            cells.animateTo(lines[page], frame, clatter, animate = onScreen.value)
            if (ticker == null) break
            ticker.awaitElapsed(pageMillis)
            page = (page + 1) % lines.size
        }
    }

    val colors = LocalBoardColors.current
    val glyphs = LocalFlapGlyphs.current ?: rememberFlapGlyphs()
    val style = rememberFlapStyle(pitch * heightDots)
    Canvas(
        modifier
            .size(pitch * Flap.width(chars), pitch * heightDots)
            .onScreen(onScreen)
            // Livello proprio: una palette che gira non fa ridisegnare il resto del tabellone.
            .graphicsLayer(),
    ) {
        val t = frame.longValue
        val p = pitch.toPx()
        val advance = DotMatrixFont.ADVANCE * p
        val faces = glyphs.faces(
            style,
            fontPx = style.fontSize.toPx(),
            width = ((DotMatrixFont.ADVANCE - Flap.GAP) * p).roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
            cornerPx = Flap.CORNER * p,
            colors = colors,
        )
        for (i in 0 until cells.size) {
            val x = (i * advance).roundToInt()
            val from = cells.from[i]
            val to = cells.target[i]
            val elapsed = t - cells.startAt[i]
            val k = if (elapsed < 0) -1 else (elapsed / Flap.FLIP_MILLIS).toInt()
            when {
                cells.steps[i] == 0 || k >= cells.steps[i] -> drawFaces(
                    x,
                    faces[to],
                    faces[to],
                    -1f,
                    colors
                )

                k < 0 -> drawFaces(x, faces[from], faces[from], -1f, colors)
                else -> drawFaces(
                    x,
                    front = faces[FlapDrum.charAfter(from, to, k)],
                    back = faces[FlapDrum.charAfter(from, to, k + 1)],
                    progress = (elapsed % Flap.FLIP_MILLIS).toFloat() / Flap.FLIP_MILLIS,
                    colors = colors,
                )
            }
        }
    }
}

/**
 * Se il modulo è almeno in parte sullo schermo. Le righe oltre quelle visibili restano composte
 * (scorrono dentro il tabellone) e cambiano testo come le altre: devono girare in silenzio,
 * altrimenti si sente un crepitio senza vedere nulla muoversi.
 */
private class OnScreen {
    var value = true
}

private fun Modifier.onScreen(state: OnScreen) = onGloballyPositioned {
    // boundsInWindow è già ritagliato dai genitori, compresa la finestra dello scorrimento.
    val bounds = it.boundsInWindow()
    state.value = bounds.width > 0f && bounds.height > 0f
}

/** Sospende finché il ticker non è avanzato di [millis] da adesso. */
private suspend fun State<Long>.awaitElapsed(millis: Long) {
    val start = value
    snapshotFlow { value }.first { it - start >= millis }
}

/** [text] su esattamente [chars] caratteri: troncato o completato con spazi secondo [align]. */
internal fun alignedLine(text: String, chars: Int, align: DotAlign): String {
    val t = text.take(chars)
    val free = chars - t.length
    return when (align) {
        DotAlign.Start -> t.padEnd(chars)
        DotAlign.End -> t.padStart(chars)
        DotAlign.Center -> " ".repeat(free / 2) + t + " ".repeat(free - free / 2)
    }
}

/**
 * Stato delle palette di un [SplitFlapText]: per ognuna il carattere di partenza, quello
 * d'arrivo, quando inizia a girare e quanti giri fa. I tempi sono quelli del frame clock.
 */
private class FlapCells(initial: String) {
    val size = initial.length
    var target = initial
        private set
    val from = initial.toCharArray()
    val startAt = LongArray(size)
    val steps = IntArray(size)
    var endAt = 0L
        private set

    /** Mette subito tutte le palette su [line]. */
    private fun snapTo(line: String) {
        line.toCharArray(from)
        steps.fill(0)
        target = line
        endAt = 0
    }

    /** Carattere fermo (o che sta per cadere) della palette [i] all'istante [t]. */
    private fun resting(i: Int, t: Long): Char {
        val elapsed = t - startAt[i]
        if (elapsed < 0) return from[i]
        return FlapDrum.charAfter(from[i], target[i], (elapsed / Flap.FLIP_MILLIS).toInt())
    }

    /**
     * Fa girare le palette verso [line] a partire da [now] (anche a metà di un'altra girata: si
     * riparte dal carattere visibile). Ritorna i ms da [now] di ogni girata, per il crepitio.
     */
    fun retarget(line: String, now: Long): IntArray {
        // Stesso testo, stessi ritardi: gli snapshot restano deterministici.
        val random = Random(line.hashCode())
        val offsets = ArrayList<Int>()
        var end = now
        for (i in 0 until size) {
            val current = resting(i, now)
            from[i] = current
            steps[i] = FlapDrum.steps(current, line[i])
            startAt[i] = now + random.nextInt(Flap.STAGGER_MILLIS)
            for (k in 1..steps[i]) offsets += (startAt[i] - now + k * Flap.FLIP_MILLIS).toInt()
            end = max(end, startAt[i] + steps[i] * Flap.FLIP_MILLIS)
        }
        target = line
        endAt = end
        return offsets.toIntArray()
    }

    /**
     * Anima fino a [line] aggiornando [frame] a ogni frame; a riposo non chiede frame. Senza
     * [animate] (modulo fuori dallo schermo) il testo cambia di colpo, senza girate né suono.
     */
    suspend fun animateTo(
        line: String,
        frame: MutableLongState,
        clatter: FlapClatter?,
        animate: Boolean
    ) {
        if (line == target) return
        val now = withFrameMillis { it }
        if (!animate) {
            snapTo(line)
            frame.longValue = now
            return
        }
        // retarget va chiamato anche senza suono: non dentro la chiamata facoltativa.
        val flips = retarget(line, now)
        clatter?.schedule(flips)
        while (true) {
            val t = withFrameMillis { it }
            frame.longValue = t
            if (t >= endAt) break
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Logo
// ---------------------------------------------------------------------------------------------

private const val LOGO_FLIP_MILLIS = 2 * Flap.FLIP_MILLIS

/**
 * Marchio dell'impresa stampato in [LocalBoardColors] `flapText` su una paletta larga [slotCols]
 * punti; null = palette vuota. Cambiando logo la palette gira una volta.
 */
@Composable
internal fun FlapLogo(
    bitmap: ImageBitmap?,
    slotCols: Int,
    modifier: Modifier = Modifier,
    sampling: DotSampling = DotSampling.Alpha,
    pitch: Dp = LocalDotPitch.current,
    heightDots: Float = Flap.HEIGHT,
) {
    val colors = LocalBoardColors.current
    val ink = remember(bitmap, sampling, colors.flapText) {
        bitmap?.let { logoInk(it, sampling, colors.flapText) }
    }
    val flip = remember { LogoFlip(ink) }
    val frame = remember { mutableLongStateOf(0L) }
    val clatter = LocalFlapClatter.current
    val onScreen = remember { OnScreen() }
    LaunchedEffect(ink, flip) {
        if (flip.to === ink) return@LaunchedEffect
        val now = withFrameMillis { it }
        // Fuori dallo schermo il logo cambia senza girare (e senza suono), come il testo.
        flip.from = if (onScreen.value) flip.to else ink
        flip.to = ink
        flip.startAt = now
        if (onScreen.value) clatter?.schedule(intArrayOf(LOGO_FLIP_MILLIS.toInt()))
        while (true) {
            val t = withFrameMillis { it }
            frame.longValue = t
            if (t >= now + LOGO_FLIP_MILLIS) break
        }
    }
    Canvas(
        modifier
            .size(pitch * slotCols, pitch * heightDots)
            .onScreen(onScreen)
            .graphicsLayer(),
    ) {
        val t = frame.longValue
        val rect = Rect(Offset.Zero, size)
        val corner = CornerRadius(Flap.CORNER * pitch.toPx())
        fun face(logo: LogoInk?): DrawScope.() -> Unit =
            { logo?.let { drawLogo(it, rect) } }

        val elapsed = t - flip.startAt
        when {
            elapsed < 0 -> drawFlap(
                rect,
                corner,
                colors,
                -1f,
                face(flip.from),
                face(flip.from)
            )

            elapsed >= LOGO_FLIP_MILLIS -> drawFlap(
                rect,
                corner,
                colors,
                -1f,
                face(flip.to),
                face(flip.to)
            )

            else -> drawFlap(
                rect, corner, colors,
                progress = elapsed.toFloat() / LOGO_FLIP_MILLIS,
                front = face(flip.from),
                back = face(flip.to),
            )
        }
    }
}

/** Logo da stampare: l'immagine, il riquadro del disegno e il filtro che lo tinge. */
private class LogoInk(val bitmap: ImageBitmap, val bounds: IntRect, val filter: ColorFilter)

private class LogoFlip(initial: LogoInk?) {
    var from: LogoInk? = initial
    var to: LogoInk? = initial

    /** Nel passato: alla prima composizione il logo è già fermo. */
    var startAt = -LOGO_FLIP_MILLIS
}

/**
 * Prepara [bitmap] da stampare in [color]: il filtro trasforma in trasparenza quello che
 * [sampling] considera inchiostro, come fa [DotMatrixImage] con i LED.
 */
private fun logoInk(bitmap: ImageBitmap, sampling: DotSampling, color: Color): LogoInk? {
    val bounds = inkBounds(bitmap.toPixelMap(), sampling) ?: return null
    val filter = when (sampling) {
        DotSampling.Alpha -> ColorFilter.tint(color, BlendMode.SrcIn)
        // L'alfa diventa la luminanza (o il suo complemento) meno la trasparenza: una matrice
        // non può moltiplicare per l'alfa, ma così i pixel trasparenti restano trasparenti.
        DotSampling.Luminance -> inkMatrix(color, 0.2126f, 0.7152f, 0.0722f, -255f)
        DotSampling.Darkness -> inkMatrix(color, -0.2126f, -0.7152f, -0.0722f, 0f)
    }
    return LogoInk(bitmap, bounds, filter)
}

private fun inkMatrix(color: Color, r: Float, g: Float, b: Float, offset: Float) =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, color.red * 255,
                0f, 0f, 0f, 0f, color.green * 255,
                0f, 0f, 0f, 0f, color.blue * 255,
                r, g, b, 1f, offset,
            ),
        ),
    )

private fun DrawScope.drawLogo(logo: LogoInk, rect: Rect) {
    val pad = rect.height * 0.14f
    val maxW = rect.width - 2 * pad
    val maxH = rect.height - 2 * pad
    val b = logo.bounds
    val scale = min(maxW / b.width, maxH / b.height)
    val w = (b.width * scale).roundToInt().coerceAtLeast(1)
    val h = (b.height * scale).roundToInt().coerceAtLeast(1)
    drawImage(
        logo.bitmap,
        srcOffset = IntOffset(b.left, b.top),
        srcSize = IntSize(b.width, b.height),
        dstOffset = IntOffset(
            (rect.center.x - w / 2f).roundToInt(),
            (rect.center.y - h / 2f).roundToInt(),
        ),
        dstSize = IntSize(w, h),
        colorFilter = logo.filter,
        filterQuality = FilterQuality.Medium,
    )
}

// ---------------------------------------------------------------------------------------------
// Disegno
// ---------------------------------------------------------------------------------------------

/**
 * Una palette in [rect]. Con [progress] < 0 è ferma e mostra [front]; tra 0 e 1 sta girando da
 * [front] a [back]: in alto si vede già la metà superiore di [back], in basso ancora quella
 * inferiore di [front]; la metà superiore di [front] cade sulla cerniera e, a metà girata,
 * diventa la metà inferiore di [back] che scende.
 */
private fun DrawScope.drawFlap(
    rect: Rect,
    corner: CornerRadius,
    colors: BoardColors,
    progress: Float,
    front: DrawScope.() -> Unit,
    back: DrawScope.() -> Unit,
) {
    val hinge = rect.center.y
    fun face(content: DrawScope.() -> Unit) {
        drawRoundRect(colors.flap, rect.topLeft, rect.size, corner)
        content()
    }
    if (progress < 0f) {
        face(front)
    } else {
        clipRect(rect.left, rect.top, rect.right, hinge) { face(back) }
        clipRect(rect.left, hinge, rect.right, rect.bottom) { face(front) }
        val pivot = Offset(rect.center.x, hinge)
        if (progress < 0.5f) {
            // Metà superiore di [front] che cade: si accorcia verso la cerniera e si scurisce.
            val s = cos(PI * progress).toFloat()
            clipRect(rect.left, rect.top, rect.right, hinge) {
                scale(1f, s, pivot) { face(front) }
                val h = (hinge - rect.top) * s
                drawRect(
                    FlapShade.copy(alpha = 0.5f * (1 - s)),
                    Offset(rect.left, hinge - h),
                    Size(rect.width, h)
                )
            }
        } else {
            // Metà inferiore di [back] che scende e si posa.
            val s = -cos(PI * progress).toFloat()
            clipRect(rect.left, hinge, rect.right, rect.bottom) {
                scale(1f, s, pivot) { face(back) }
                val h = (rect.bottom - hinge) * s
                drawRect(
                    FlapShade.copy(alpha = 0.4f * (1 - s)),
                    Offset(rect.left, hinge),
                    Size(rect.width, h)
                )
            }
        }
    }
    drawLine(
        colors.flapHinge,
        Offset(rect.left, hinge),
        Offset(rect.right, hinge),
        strokeWidth = max(1f, rect.height * 0.03f),
    )
}

private val FlapShade = Color.Black

/**
 * Come [drawFlap], ma con le facce già pronte in bitmap ([FlapFaces]): una palette di testo si
 * disegna con qualche drawImage, molto meno di un testo da impaginare a ogni frame.
 */
private fun DrawScope.drawFaces(
    x: Int,
    front: ImageBitmap,
    back: ImageBitmap,
    progress: Float,
    colors: BoardColors,
) {
    val w = front.width
    val h = front.height
    val half = h / 2
    if (progress < 0f) {
        drawImage(front, Offset(x.toFloat(), 0f))
        return
    }
    drawImage(back, IntOffset.Zero, IntSize(w, half), IntOffset(x, 0), IntSize(w, half))
    drawImage(
        front,
        IntOffset(0, half),
        IntSize(w, h - half),
        IntOffset(x, half),
        IntSize(w, h - half)
    )
    if (progress < 0.5f) {
        val s = cos(PI * progress).toFloat()
        val fall = (half * s).roundToInt()
        if (fall > 0) {
            drawImage(
                front,
                IntOffset.Zero,
                IntSize(w, half),
                IntOffset(x, half - fall),
                IntSize(w, fall)
            )
            drawRect(
                FlapShade.copy(alpha = 0.5f * (1 - s)),
                Offset(x.toFloat(), (half - fall).toFloat()),
                Size(w.toFloat(), fall.toFloat()),
            )
        }
    } else {
        val s = -cos(PI * progress).toFloat()
        val drop = ((h - half) * s).roundToInt()
        if (drop > 0) {
            drawImage(
                back,
                IntOffset(0, half),
                IntSize(w, h - half),
                IntOffset(x, half),
                IntSize(w, drop)
            )
            drawRect(
                FlapShade.copy(alpha = 0.4f * (1 - s)),
                Offset(x.toFloat(), half.toFloat()),
                Size(w.toFloat(), drop.toFloat()),
            )
        }
    }
    // Le facce hanno già la cerniera, ma la palette che cade la copre.
    drawHinge(x.toFloat(), (x + w).toFloat(), half.toFloat(), colors)
}

private fun DrawScope.drawHinge(left: Float, right: Float, y: Float, colors: BoardColors) {
    drawLine(
        colors.flapHinge,
        Offset(left, y),
        Offset(right, y),
        strokeWidth = max(1f, size.height * 0.03f)
    )
}

/** Lettera centrata sulla cerniera e ristretta di [condense] in orizzontale. */
private fun DrawScope.drawGlyph(
    layout: TextLayoutResult,
    color: Color,
    rect: Rect,
    condense: Float,
    fontPx: Float,
) {
    val hinge = rect.center.y
    val top = hinge - layout.firstBaseline + Flap.CAP_HEIGHT * fontPx / 2
    scale(condense, 1f, Offset(rect.center.x, hinge)) {
        drawText(layout, color, Offset(rect.center.x - layout.size.width / 2f, top))
    }
}

// ---------------------------------------------------------------------------------------------
// Lettere
// ---------------------------------------------------------------------------------------------

/**
 * Lettere delle palette già misurate, condivise da tutto il tabellone: le stesse poche decine di
 * caratteri si ripetono in centinaia di palette.
 */
internal class FlapGlyphs(private val measurer: TextMeasurer) {
    private val layouts = HashMap<Pair<Char, Float>, TextLayoutResult>()
    private val condensed = HashMap<Pair<Float, Float>, Float>()
    private val faceSets = LinkedHashMap<FaceKey, FlapFaces>()

    /**
     * Facce delle palette di una misura (testo, orologio, data…), create alla prima richiesta.
     * Cambiando misura (finestra ridimensionata) le più vecchie si buttano.
     */
    fun faces(
        style: TextStyle,
        fontPx: Float,
        width: Int,
        height: Int,
        cornerPx: Float,
        colors: BoardColors,
    ): FlapFaces {
        val key =
            FaceKey(style.fontSize.value, width, height, cornerPx, colors.flap, colors.flapText)
        faceSets[key]?.let { return it }
        if (faceSets.size >= MAX_FACE_SETS) faceSets.remove(faceSets.keys.first())
        val condense = condense(style, width.toFloat())
        return FlapFaces { c ->
            rasterize(width, height) {
                drawRoundRect(colors.flap, Offset.Zero, size, CornerRadius(cornerPx))
                if (c != ' ') {
                    val rect = Rect(Offset.Zero, size)
                    // Il disegno su bitmap ha densità 1: il corpo in px arriva da chi chiede.
                    drawGlyph(layout(c, style), colors.flapText, rect, condense, fontPx)
                }
                drawHinge(0f, size.width, (height / 2).toFloat(), colors)
            }.also { it.prepareToDraw() }
        }.also { faceSets[key] = it }
    }

    private data class FaceKey(
        val fontSize: Float,
        val width: Int,
        val height: Int,
        val cornerPx: Float,
        val flap: Color,
        val text: Color,
    )

    private companion object {
        const val MAX_FACE_SETS = 8
    }

    fun layout(c: Char, style: TextStyle): TextLayoutResult =
        layouts.getOrPut(c to style.fontSize.value) {
            measurer.measure(c.toString(), style, softWrap = false, maxLines = 1)
        }

    /** Restringimento orizzontale perché anche le lettere più larghe entrino nella palette. */
    fun condense(style: TextStyle, cellWidthPx: Float): Float =
        condensed.getOrPut(style.fontSize.value to cellWidthPx) {
            val widest = max(layout('W', style).size.width, layout('M', style).size.width)
            if (widest == 0) 1f else min(1f, cellWidthPx * 0.84f / widest)
        }
}

/** Bitmap di ogni carattere stampato su una palette, create la prima volta che servono. */
internal class FlapFaces(private val render: (Char) -> ImageBitmap) {
    private val faces = HashMap<Char, ImageBitmap>()

    operator fun get(c: Char): ImageBitmap = faces.getOrPut(c) { render(c) }
}

internal val LocalFlapGlyphs = staticCompositionLocalOf<FlapGlyphs?> { null }

@Composable
internal fun rememberFlapGlyphs(): FlapGlyphs {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(measurer) { FlapGlyphs(measurer) }
}

@Composable
private fun rememberFlapStyle(height: Dp): TextStyle {
    val fontSize = with(LocalDensity.current) { (height * Flap.FONT).toSp() }
    return remember(fontSize) {
        TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )
    }
}
