package announce

import rfi.BoardMode
import rfi.RfiTrain

/**
 * Un evento da annunciare, ricavato confrontando due versioni del tabellone. [code] è il codice
 * dell'annuncio nel Manuale degli Annunci Sonori RFI (P = partenze, A = arrivi).
 */
sealed class Announcement {
    abstract val train: RfiTrain
    abstract val mode: BoardMode
    abstract val code: String

    /**
     * Il treno è in partenza / in arrivo (si accendono le spie): P1, P2 se il binario è cambiato,
     * P16 per l'autobus sostitutivo; A1, A2 in arrivo.
     */
    data class Imminent(
        override val train: RfiTrain,
        override val mode: BoardMode,
        /** Binario mostrato prima, se diverso da quello attuale. */
        val previousPlatform: String? = null,
    ) : Announcement() {
        override val code
            get() = when {
                departures && train.isBus -> "P16"
                departures -> if (previousPlatform != null) "P2" else "P1"
                else -> if (previousPlatform != null) "A2" else "A1"
            }
    }

    /**
     * Il monitor mostra il binario di un treno che prima non l'aveva: P8 (e la stessa formula per
     * l'arrivo, come A4) senza "invece che dal binario…", che il manuale usa solo se il binario varia.
     */
    data class PlatformAssigned(
        override val train: RfiTrain,
        override val mode: BoardMode,
    ) : Announcement() {
        override val code get() = if (departures) "P8" else "A4"
    }

    /** Variazione di binario prima della partenza / dell'arrivo: P8, A4. */
    data class PlatformChange(
        override val train: RfiTrain,
        override val mode: BoardMode,
        val previousPlatform: String,
    ) : Announcement() {
        override val code get() = if (departures) "P8" else "A4"
    }

    /** Ritardo di almeno [BoardAnnouncements.DELAY_THRESHOLD] minuti: P5, A3. */
    data class Delay(
        override val train: RfiTrain,
        override val mode: BoardMode,
        val minutes: Int,
        /** Ritardo già annunciato, se diverso: il MAS aggiunge "diversamente da quanto già annunciato". */
        val previouslyAnnounced: Int? = null,
    ) : Announcement() {
        override val code get() = if (departures) "P5" else "A3"
    }

    /** Treno soppresso: P9, A5. */
    data class Cancelled(
        override val train: RfiTrain,
        override val mode: BoardMode,
    ) : Announcement() {
        override val code get() = if (departures) "P9" else "A5"
    }
}

internal val Announcement.departures get() = mode == BoardMode.Departures

internal val RfiTrain.isBus get() = category.trim().equals("BUS", ignoreCase = true)

/** "P1 AV 9915 BARI C.LE 07:40", per i log. */
internal fun Announcement.summary() = "$code ${train.describe()}"
