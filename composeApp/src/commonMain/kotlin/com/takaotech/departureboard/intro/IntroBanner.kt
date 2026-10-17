package com.takaotech.departureboard.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow

/**
 * Quanto è aperto il banner a [millis]: sale con lo stinger in 250 ms, resta, scende in
 * [IntroTimeline.BANNER_OUT] da [IntroTimeline.BANNER_OUT_AT].
 */
internal fun bannerAmount(millis: Long): Float {
    val open = IntroTimeline.progress(millis, IntroTimeline.BANNER_AT + 100, 250)
    val close =
        IntroTimeline.progress(millis, IntroTimeline.BANNER_OUT_AT, IntroTimeline.BANNER_OUT)
    return easeInOut(open) * (1f - easeInOut(close))
}

/**
 * "TABELLONE ATTIVATO" su una fascia scura, tra fasce diagonali verde opale (a sinistra) e rosso
 * FS (a destra), con cinque loghi FS sotto al posto delle stelle del video.
 */
@Composable
internal fun IntroBanner(millis: () -> Long, fonts: IntroFonts, units: IntroUnits) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val a = bannerAmount(millis())
            alpha = a
            scaleY = 0.2f + 0.8f * a
        },
    ) {
        Column(
            Modifier.align(BiasAlignment(0f, -0.12f)).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().height(units * 96)
                    .background(IntroColors.Panel.copy(alpha = 0.78f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stripes(
                    IntroColors.LogoGreen,
                    Modifier.weight(0.22f).fillMaxHeight().padding(vertical = units * 10)
                )
                Text(
                    "TABELLONE ATTIVATO",
                    style = units.jost(fonts, 66, IntroColors.White),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = units * 18),
                )
                Stripes(
                    IntroColors.LogoRed,
                    Modifier.weight(0.22f).fillMaxHeight().padding(vertical = units * 10)
                )
            }
            Row(
                Modifier.padding(top = units * 14),
                horizontalArrangement = Arrangement.spacedBy(units * 22),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(0.7f, 0.85f, 1.25f, 0.85f, 0.7f).forEach { scale ->
                    Canvas(Modifier.size(units * (34 * scale), units * (24 * scale))) {
                        drawFsLogo(Offset.Zero, size, IntroColors.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stripes(color: Color, modifier: Modifier) {
    Canvas(modifier) { drawHatch(color, size.height * 0.42f, size.height * 0.2f) }
}
