package com.takaotech.departureboard.intro

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.jost_medium
import com.takaotech.departureboard.resources.jost_semibold
import com.takaotech.departureboard.resources.share_tech_mono_regular
import org.jetbrains.compose.resources.Font
import kotlin.math.floor

/**
 * Colori dell'intro, assegnati con i ruoli Material 3 dello schema dell'app
 * ([com.takaotech.departureboard.AppTheme]):
 * - primario (giallo segnale) per gli elementi chiave: comandi battuti, indicatori, il tabellone
 *   e il suo conto alla rovescia, l'azione SKIP;
 * - secondario (blu oltremare) per gli elementi di supporto: barre e etichette del terminale
 *   come contenitori, righe di rete, traffico, stati selezionati, avvisi;
 * - superfici e contenitori per fondi e pannelli, on-surface per i testi, outline per i bordi;
 * - errore (rosso) per errori, timeout e segnali a via impedita.
 * Il logo FS a colori resta nei colori del marchio, che non dipendono dal tema.
 */
@Immutable
internal class IntroColors(scheme: ColorScheme) {
    val background = scheme.surfaceContainerLowest
    val surface = scheme.surface
    val panel = scheme.surfaceContainerLow
    val tile = scheme.surfaceContainer
    val tileHigh = scheme.surfaceContainerHighest
    val text = scheme.onSurface
    val textDim = scheme.onSurfaceVariant
    val outline = scheme.outline
    val outlineVariant = scheme.outlineVariant
    val primary = scheme.primary
    val onPrimary = scheme.onPrimary
    val secondary = scheme.secondary
    val secondaryContainer = scheme.secondaryContainer
    val onSecondaryContainer = scheme.onSecondaryContainer

    /** Rosso pieno: luci dei segnali, lancetta dei secondi. */
    val error = scheme.errorContainer

    /** Rosso leggibile come testo su fondo scuro. */
    val errorText = scheme.error
    val clockFace = scheme.inverseSurface
    val clockHands = scheme.surfaceContainerLowest
    val scrim = scheme.scrim
    val logoGreen = FsLogo.LightGreen
    val logoRed = FsLogo.Red
}

/** [IntroColors] del tema corrente. */
@Composable
internal fun introColors(): IntroColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { IntroColors(scheme) }
}

/** Font dell'intro: Share Tech Mono per il terminale, Jost per i titoli del cruscotto. */
internal class IntroFonts(val mono: FontFamily, val jost: FontFamily)

@Composable
internal fun rememberIntroFonts(): IntroFonts {
    val mono = Font(Res.font.share_tech_mono_regular)
    val medium = Font(Res.font.jost_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.jost_semibold, FontWeight.SemiBold)
    return remember(mono, medium, semiBold) {
        IntroFonts(FontFamily(mono), FontFamily(medium, semiBold))
    }
}

/**
 * Unità di disegno dell'intro: le misure sono pensate su uno schermo di riferimento di
 * 1600×900 e scalano con quello vero.
 */
internal class IntroUnits(val u: Dp, private val density: Density) {
    operator fun times(value: Number): Dp = u * value.toFloat()

    /** [value] unità come dimensione di testo, indipendente dalla scala dei caratteri di sistema. */
    fun sp(value: Number): TextUnit = with(density) { (u * value.toFloat()).toSp() }

    fun px(value: Number): Float = with(density) { (u * value.toFloat()).toPx() }

    fun mono(fonts: IntroFonts, size: Number, color: Color) = TextStyle(
        fontFamily = fonts.mono,
        fontSize = sp(size),
        lineHeight = sp(size.toFloat() * 1.25f),
        color = color,
    )

    fun jost(fonts: IntroFonts, size: Number, color: Color) = TextStyle(
        fontFamily = fonts.jost,
        fontWeight = FontWeight.SemiBold,
        fontSize = sp(size),
        lineHeight = sp(size.toFloat() * 1.15f),
        color = color,
    )
}

/** Valore pseudocasuale 0..1 sempre uguale per ([seed], [i]). */
internal fun hash01(seed: Int, i: Long): Float {
    var h = seed.toLong() * 0x9E3779B97F4A7C15uL.toLong() + i * 0xBF58476D1CE4E5B9uL.toLong()
    h = (h xor (h ushr 30)) * 0x94D049BB133111EBuL.toLong()
    h = h xor (h ushr 31)
    return ((h ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF
}

/** Rumore continuo 0..1 lungo [x], con [seed]: passa da un valore casuale all'altro a ogni intero. */
internal fun noise(seed: Int, x: Float): Float {
    val i = floor(x)
    val f = x - i
    val t = f * f * (3 - 2 * f)
    val a = hash01(seed, i.toLong())
    val b = hash01(seed, i.toLong() + 1)
    return a + (b - a) * t
}

/** Easing morbido in entrata e in uscita. */
internal fun easeInOut(t: Float): Float = t * t * (3 - 2 * t)
