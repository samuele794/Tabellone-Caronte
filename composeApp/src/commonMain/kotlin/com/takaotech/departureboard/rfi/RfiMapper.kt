package com.takaotech.departureboard.rfi

import androidx.compose.ui.graphics.ImageBitmap
import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.Lamps
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.io.encoding.Base64

/** Adatta le righe del monitor RFI al modello del tabellone. */
class RfiMapper {

    /**
     * Logo decodificati, per stringa base64. Così a ogni aggiornamento la stessa [ImageBitmap]
     * torna identica e [com.takaotech.departureboard.DotMatrixImage] non ricalcola i punti.
     */
    private val logos = HashMap<String, ImageBitmap?>()

    fun departures(board: RfiBoard): List<Departure> = board.trains.map(::departure)

    fun departure(train: RfiTrain) = Departure(
        category = categoryCode(train.category),
        number = train.number,
        destination = train.station,
        time = train.time,
        delayMinutes = train.delayMinutes,
        delayText = train.delayText,
        // Le note seguono le fermate nella stessa colonna.
        info = (listOf(train.info) + train.notes).filter { it.isNotBlank() }.joinToString(" - "),
        platform = train.platform,
        // GIF su fondo trasparente (DotSampling.Alpha, il default) con scritte a tratto sottile.
        logo = train.carrierLogoBase64?.let(::logo),
        logoThreshold = LOGO_THRESHOLD,
        lamps = if (train.flashing) Lamps.Blinking else Lamps.Off,
    )

    private fun logo(base64: String): ImageBitmap? = logos.getOrPut(base64) {
        runCatching { Base64.decode(base64).decodeToImageBitmap() }.getOrNull()
    }

    companion object {
        /** Soglia per i marchi RFI: più bassa del default, altrimenti le lettere si spezzano. */
        const val LOGO_THRESHOLD = 0.35f

        /** Colonne del tabellone riservate al logo. */
        const val LOGO_COLS = 24

        /**
         * LED del logo per punto del tabellone: i marchi RFI sono scritte molto larghe (fino a
         * 11:1, es. TRENORD) che in 7 righe di punti diventerebbero una striscia.
         */
        const val LOGO_DENSITY = 3

        private val CATEGORIES = mapOf(
            "ALTA VELOCITA'" to "AV",
            "INTERCITY" to "IC",
            "INTERCITY NOTTE" to "ICN",
            "REG" to "R",
            "REGIONALE" to "R",
            "REGIONALE VELOCE" to "RV",
            "EUROCITY" to "EC",
            "EURONIGHT" to "EN",
            "ES*" to "ES",
            "BUS" to "BUS",
        )

        /** "ALTA VELOCITA'" → "AV"; categorie sconosciute: prime 3 lettere. */
        fun categoryCode(category: String): String {
            val key = category.trim().uppercase()
            return CATEGORIES[key] ?: key.filter { it.isLetterOrDigit() }.take(3)
        }
    }
}
