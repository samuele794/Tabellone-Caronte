package com.takaotech.departureboard.wear.data

import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Quando aggiornare il treno seguito. Il dato cambia solo attorno alla partenza: da
 * [WINDOW_BEFORE] prima a [WINDOW_AFTER] dopo (ritardo compreso). Fuori da lì il watch non chiede
 * nulla al telefono e non accende la radio; dentro, più spesso man mano che il treno si avvicina.
 *
 * L'orario è quello del monitor ([WatchSnapshot.train], che resta anche quando il treno non è in
 * tabellone) o quello della scelta; vale ogni giorno, perché il treno è ricorrente.
 */
object RefreshPolicy {

    /** Gli orari del monitor sono quelli italiani, qualunque sia il fuso del watch. */
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")

    val WINDOW_BEFORE = 90.minutes
    val WINDOW_AFTER = 20.minutes

    /** Da qui alla partenza il treno è "vicino": binario e ritardo cambiano più spesso. */
    val NEAR = 30.minutes

    val FAR_BACKGROUND = 10.minutes
    val FAR_FOREGROUND = 3.minutes
    val NEAR_BACKGROUND = 3.minutes
    val NEAR_FOREGROUND = 1.minutes

    /** App aperta lontano dalla partenza. */
    val IDLE_FOREGROUND = 10.minutes

    /** Orario mai visto: si controlla di rado finché il treno non compare in tabellone. */
    val UNKNOWN_TIME = 30.minutes

    /** Dopo un errore del monitor non si insiste. */
    val AFTER_ERROR = 10.minutes

    /** Massima attesa tra due tentativi senza connessione. */
    val BACKOFF_MAX = 40.minutes

    /**
     * Quando fare il prossimo aggiornamento (millisecondi dall'epoch; già passato = subito), con
     * l'app aperta ([foreground]) o in background. null se non c'è nessun treno da seguire.
     */
    fun nextRefreshAt(snapshot: WatchSnapshot, now: Long, foreground: Boolean): Long? {
        if (snapshot.selection == null) return null
        val last = snapshot.lastAttemptAt
        if (last == 0L) return now
        val departure = departure(snapshot, now) ?: return last + backoff(snapshot, UNKNOWN_TIME)
        if (now < departure.start) {
            // In background si riparte all'inizio della fascia; con l'app aperta ogni tanto.
            return if (foreground) minOf(
                last + backoff(snapshot, IDLE_FOREGROUND),
                departure.start
            ) else departure.start
        }
        val near = departure.at - now <= NEAR.inWholeMilliseconds
        val interval = when {
            near && foreground -> NEAR_FOREGROUND
            near -> NEAR_BACKGROUND
            foreground -> FAR_FOREGROUND
            else -> FAR_BACKGROUND
        }
        return last + backoff(snapshot, interval)
    }

    /**
     * Senza connessione l'attesa raddoppia: è pari al tempo passato dall'ultimo dato buono, fino
     * a [BACKOFF_MAX]. Dopo un errore del monitor, almeno [AFTER_ERROR].
     */
    private fun backoff(snapshot: WatchSnapshot, interval: Duration): Long {
        var wait = interval.inWholeMilliseconds
        if (snapshot.status == WatchStatus.Error) wait =
            maxOf(wait, AFTER_ERROR.inWholeMilliseconds)
        if (snapshot.offline != null && snapshot.updatedAt > 0) {
            val gap = snapshot.lastAttemptAt - snapshot.updatedAt
            wait = maxOf(wait, minOf(gap, BACKOFF_MAX.inWholeMilliseconds))
        }
        return wait
    }

    /** Partenza [at] (con il ritardo noto) e fascia da [start] a [end], in millisecondi. */
    private class Departure(val at: Long, val start: Long, val end: Long)

    /**
     * La partenza da seguire adesso: quella più vicina a [now] (ieri, oggi o domani, per i treni
     * attorno alla mezzanotte), o quella del giorno dopo se la sua fascia è finita o il treno è
     * già partito. null se l'orario non si conosce.
     */
    private fun departure(snapshot: WatchSnapshot, now: Long): Departure? {
        val time =
            (snapshot.train?.time ?: snapshot.selection?.time)?.let(::parseTime) ?: return null
        val today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        val nearest = (-1L..1L).map { today.plusDays(it).atTime(time).atZone(ZONE) }
            .minBy { abs(it.toInstant().toEpochMilli() - now) }
        val scheduled = nearest.toInstant().toEpochMilli()
        // Ritardo e "ancora in tabellone" valgono solo se visti per questa partenza, non ieri.
        val onBoard = snapshot.status == WatchStatus.OnBoard &&
                snapshot.updatedAt >= scheduled - WINDOW_BEFORE.inWholeMilliseconds
        val delay = (snapshot.train?.delayMinutes?.takeIf { onBoard } ?: 0).coerceAtLeast(0).minutes
        val at = scheduled + delay.inWholeMilliseconds
        val current = Departure(
            at = at,
            start = scheduled - WINDOW_BEFORE.inWholeMilliseconds,
            // Ancora in tabellone all'ultimo controllo: si segue finché non sparisce.
            end = maxOf(
                at + WINDOW_AFTER.inWholeMilliseconds,
                if (onBoard) snapshot.updatedAt + WINDOW_AFTER.inWholeMilliseconds else 0,
            ),
        )
        // Sparito dal monitor dopo l'orario: è partito (o soppresso), per oggi basta.
        val departed = snapshot.status == WatchStatus.NotOnBoard && snapshot.updatedAt >= at
        if (now < current.end && !departed) return current
        val next = nearest.plusDays(1).toInstant().toEpochMilli()
        return Departure(
            at = next,
            start = next - WINDOW_BEFORE.inWholeMilliseconds,
            end = next + WINDOW_AFTER.inWholeMilliseconds,
        )
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    }
}
