package com.takaotech.departureboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.jost_medium
import com.takaotech.departureboard.resources.jost_regular
import com.takaotech.departureboard.resources.jost_semibold
import org.jetbrains.compose.resources.Font

/**
 * Schema Material 3 dell'app, solo scuro, ispirato alla livrea RFI dei treni diagnostici e al
 * logo FS. Ricavato con le Material Color Utilities (variante tonal spot) dai colori chiave:
 * - primario: Giallo Segnale RAL 1003 (#FEB50C), fascia intermedia della livrea;
 * - secondario: Blu Oltremare RAL 5002 (#172670), fasce superiore e inferiore;
 * - terziario: Verde Opale RAL 6026 (#2A6462), logo FS;
 * - errore: Rosso Traffico RAL 3020 (#CC353C), logo FS.
 * Primario, secondaryContainer e tertiaryContainer sono i colori chiave esatti, il resto viene
 * dalle loro palette tonali.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFEB50C),
    onPrimary = Color(0xFF422C00),
    primaryContainer = Color(0xFF5F4100),
    onPrimaryContainer = Color(0xFFFFDEAB),
    inversePrimary = Color(0xFF7D5700),
    secondary = Color(0xFFBAC3FF),
    onSecondary = Color(0xFF1A2972),
    secondaryContainer = Color(0xFF172670),
    onSecondaryContainer = Color(0xFFDEE0FF),
    tertiary = Color(0xFF98D1CE),
    onTertiary = Color(0xFF003736),
    tertiaryContainer = Color(0xFF2A6462),
    onTertiaryContainer = Color(0xFFB3EDEA),
    background = Color(0xFF16130E),
    onBackground = Color(0xFFEAE1D9),
    surface = Color(0xFF16130E),
    onSurface = Color(0xFFEAE1D9),
    surfaceVariant = Color(0xFF4E4539),
    onSurfaceVariant = Color(0xFFD2C5B4),
    surfaceTint = Color(0xFFFEB50C),
    inverseSurface = Color(0xFFEAE1D9),
    inverseOnSurface = Color(0xFF34302A),
    error = Color(0xFFFFB3B0),
    onError = Color(0xFF68000F),
    errorContainer = Color(0xFF920119),
    onErrorContainer = Color(0xFFFFDAD8),
    outline = Color(0xFF9A8F80),
    outlineVariant = Color(0xFF4E4539),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3D3833),
    surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2D2924),
    surfaceContainerHighest = Color(0xFF38342E),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainerLowest = Color(0xFF110E09),
    surfaceDim = Color(0xFF16130E),
)

/**
 * Tipografia Material 3 in Jost (OFL), alternativa libera al Futura Serie BQ della livrea RFI.
 * Display, headline e title in SemiBold richiamano il Futura Demi; il tabellone usa un suo font e
 * non ne è toccato.
 */
@Composable
private fun appTypography(): Typography {
    val regular = Font(Res.font.jost_regular, FontWeight.Normal)
    val medium = Font(Res.font.jost_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.jost_semibold, FontWeight.SemiBold)
    val jost = remember(regular, medium, semiBold) { FontFamily(regular, medium, semiBold) }
    return remember(jost) {
        val base = Typography()
        fun TextStyle.jost(weight: FontWeight = fontWeight ?: FontWeight.Normal) =
            copy(fontFamily = jost, fontWeight = weight)
        Typography(
            displayLarge = base.displayLarge.jost(FontWeight.SemiBold),
            displayMedium = base.displayMedium.jost(FontWeight.SemiBold),
            displaySmall = base.displaySmall.jost(FontWeight.SemiBold),
            headlineLarge = base.headlineLarge.jost(FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.jost(FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.jost(FontWeight.SemiBold),
            titleLarge = base.titleLarge.jost(FontWeight.SemiBold),
            titleMedium = base.titleMedium.jost(FontWeight.SemiBold),
            titleSmall = base.titleSmall.jost(FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.jost(),
            bodyMedium = base.bodyMedium.jost(),
            bodySmall = base.bodySmall.jost(),
            labelLarge = base.labelLarge.jost(),
            labelMedium = base.labelMedium.jost(),
            labelSmall = base.labelSmall.jost(),
        )
    }
}

/** Tema dell'app: sempre scuro, indipendentemente dall'impostazione di sistema. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = appTypography(), content = content)
}
