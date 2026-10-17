package com.takaotech.departureboard.intro

import androidx.compose.runtime.Composable
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
 * Colori dell'intro: il verde opale del logo FS al posto del ciano del terminale del video,
 * il rosso FS per errori e timeout, il giallo segnale (RAL 1003) del cruscotto.
 */
internal object IntroColors {
    val Background = Color(0xFF04100F)
    val Panel = Color(0xFF061816)
    val Text = Color(0xFF98D1CE)
    val TextBright = Color(0xFFD4F5F2)
    val TextDim = Color(0xFF5C9894)
    val Line = Color(0xFF2A6462)
    val Bar = Color(0xFF4FB8B1)
    val BarTrack = Color(0xFF123634)
    val Header = Color(0xFF2A6462)
    val HeaderText = Color(0xFFE6FFFC)
    val Error = Color(0xFFDC062B)
    val ErrorBright = Color(0xFFFF4A5F)
    val LogoGreen = Color(0xFF2E9C96)
    val LogoRed = Color(0xFFDC062B)
    val Signal = Color(0xFFFEB50C)
    val DashBackground = Color(0xFF0B0E10)
    val Tile = Color(0xFF14191C)
    val White = Color(0xFFF1F1EE)
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

    fun mono(fonts: IntroFonts, size: Number, color: Color = IntroColors.Text) = TextStyle(
        fontFamily = fonts.mono,
        fontSize = sp(size),
        lineHeight = sp(size.toFloat() * 1.25f),
        color = color,
    )

    fun jost(fonts: IntroFonts, size: Number, color: Color = IntroColors.White) = TextStyle(
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
