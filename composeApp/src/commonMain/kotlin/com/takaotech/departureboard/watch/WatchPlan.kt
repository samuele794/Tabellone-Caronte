package com.takaotech.departureboard.watch

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * I treni seguiti sullo smartwatch, anche da stazioni diverse. Il watch ne mostra uno alla volta:
 * il primo, nell'ordine di osservazione, che oggi non è ancora partito.
 *
 * Ordine di osservazione: i treni di cui si conosce l'orario si mettono in ordine di partenza; quelli
 * scelti solo per numero restano dove li ha messi l'utente finché non compaiono in tabellone, e
 * allora vanno al loro posto.
 *
 * Partito: visto in tabellone e poi sparito. Un treno che non si è visto sparire (watch spento,
 * nessuna rete) si dà per partito [GONE_AFTER] dopo l'orario, ritardo compreso. Ogni giorno si
 * riparte dal primo; la giornata dei treni comincia alle [DAY_START].
 */
object WatchPlan {

    /** Gli orari del monitor sono quelli italiani, qualunque sia il fuso del dispositivo. */
    val ZONE: TimeZone = TimeZone.of("Europe/Rome")

    /** Un treno delle 00:20 viene dopo quello delle 23:50 della sera prima. */
    val DAY_START = LocalTime(3, 0)

    val GONE_AFTER = 20.minutes

    /** Giornata dei treni di [at]: quella del calendario, ma dalle [DAY_START]. */
    fun serviceDay(at: Instant): LocalDate =
        (at - DAY_START.hour.hours).toLocalDateTime(ZONE).date

    /** Partenza programmata alle [time] ("HH:MM") nella giornata [day]; null se non si legge. */
    fun scheduled(time: String, day: LocalDate): Instant? {
        val clock = parseTime(time) ?: return null
        val date = if (clock < DAY_START) day.plus(DatePeriod(days = 1)) else day
        return LocalDateTime(date, clock).toInstant(ZONE)
    }

    /**
     * [trains] in ordine di osservazione: quelli con orario si riordinano tra loro, nei posti che
     * già occupano; quelli senza restano fermi.
     */
    fun ordered(trains: List<WatchSelection>): List<WatchSelection> {
        val known = trains.filter { minuteOfDay(it.time) != null }
            .sortedBy { minuteOfDay(it.time) }
            .iterator()
        return trains.map { if (minuteOfDay(it.time) != null) known.next() else it }
    }

    /**
     * [trains] con etichetta e orario presi da [known], per quelli ancora senza, e nell'ordine
     * giusto.
     */
    fun withDetails(
        trains: List<WatchSelection>,
        known: List<WatchSelection>
    ): List<WatchSelection> =
        ordered(trains.map { train -> train.orDetailsOf(known.firstOrNull(train::sameTrain)) })

    /** [trains] con anche [train], al suo posto; se c'è già se ne tengono i dati migliori. */
    fun added(trains: List<WatchSelection>, train: WatchSelection): List<WatchSelection> =
        if (trains.any(train::sameTrain)) withDetails(
            trains,
            listOf(train)
        ) else ordered(trains + train)

    /** Minuti da [DAY_START]; null se l'orario non si conosce. */
    private fun minuteOfDay(time: String?): Int? {
        val clock = time?.let(::parseTime) ?: return null
        return (clock.hour * 60 + clock.minute - DAY_START.hour * 60).mod(24 * 60)
    }

    private fun parseTime(text: String): LocalTime? {
        val parts = text.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return LocalTime(hour, minute)
    }
}
