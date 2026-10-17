package com.takaotech.departureboard.intro

import androidx.compose.runtime.Immutable
import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.rfi.Station
import kotlinx.datetime.LocalDate

/**
 * Quello che l'intro mostra del tabellone: stazione (il terminale "si collega" a lei), treni e
 * avvisi per il cruscotto, impostazioni per il riquadro CONFIG. [decoy] è la stazione che il
 * terminale prova per prima e che non risponde (null: la stazione 81 del compartimento MI).
 * [startMillisOfDay] è l'ora locale all'inizio dell'intro: registro e orologio vanno avanti da lì.
 */
@Immutable
data class IntroInfo(
    val station: Station,
    val decoy: Station? = null,
    val modeLabel: String = "PARTENZE",
    val sourceLabel: String = "RFI",
    val departures: List<Departure> = emptyList(),
    val notices: List<String> = emptyList(),
    val config: List<Pair<String, String>> = emptyList(),
    val date: LocalDate = LocalDate(2026, 1, 1),
    val startMillisOfDay: Long = 12L * 3_600_000 + 13 * 60_000 + 40_000,
) {
    /** Millisecondi dalla mezzanotte a [millis] dall'inizio dell'intro. */
    fun millisOfDayAt(millis: Long): Long = (startMillisOfDay + millis).mod(DAY)

    /** "hh:mm:ss" a [millis] dall'inizio dell'intro. */
    fun clockAt(millis: Long): String {
        val s = millisOfDayAt(millis) / 1000
        return "${two(s / 3600)}:${two(s / 60 % 60)}:${two(s % 60)}"
    }

    /**
     * Secondi alla prossima partenza (orario + ritardo) a [millis] dall'inizio dell'intro, e il
     * treno; null se non ce ne sono nelle prossime 12 ore.
     */
    fun nextDeparture(millis: Long): Pair<Departure, Long>? {
        val now = millisOfDayAt(millis) / 1000
        return departures.mapNotNull { departure ->
            val parts = departure.time.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val at = (h * 3600L + (m + (departure.delayMinutes ?: 0)) * 60L)
            val left = (at - now).mod(DAY / 1000)
            if (left < 12 * 3600) departure to left else null
        }.minByOrNull { it.second }
    }

    private companion object {
        const val DAY = 86_400_000L
        fun two(n: Long) = n.toString().padStart(2, '0')
    }
}
