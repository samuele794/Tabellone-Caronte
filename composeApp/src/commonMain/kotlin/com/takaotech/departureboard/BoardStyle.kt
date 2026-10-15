package com.takaotech.departureboard

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable

/** Aspetto del tabellone: stesse colonne e misure, cambia come si scrive il testo. */
@Serializable
enum class BoardStyle {
    /** LED ambra a matrice di punti 5×7. */
    Led,

    /** Palette Solari: lettere bianche su palette nere che girano quando il testo cambia. */
    SplitFlap,
}

val LocalBoardStyle = staticCompositionLocalOf { BoardStyle.Led }
