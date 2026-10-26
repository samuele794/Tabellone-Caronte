package com.takaotech.departureboard.wear.data

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.WatchOffline
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import com.takaotech.departureboard.watch.WatchedTrain
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class RefreshPolicyTest {

    private val selection = WatchSelection(
        StationSource.Rfi,
        Station("837", "BOLOGNA CENTRALE"),
        "91831",
        "R 91831 → VIGNOLA",
        time = "19:18",
    )

    private val train = WatchedTrain(
        carrier = "TRENITALIA TPER",
        category = "R",
        number = "91831",
        destination = "VIGNOLA",
        time = "19:18",
    )

    /** Ora italiana del 26 ottobre 2026, in millisecondi. */
    private fun at(time: String, day: Int = 26): Long =
        LocalDateTime.parse("2026-10-${day.toString().padStart(2, '0')}T$time")
            .atZone(RefreshPolicy.ZONE).toInstant().toEpochMilli()

    private fun snapshot(
        updatedAt: Long,
        status: WatchStatus = WatchStatus.NotOnBoard,
        train: WatchedTrain? = null
    ) =
        WatchSnapshot(selection, train, status, updatedAt)

    private operator fun Long.plus(d: Duration) = this + d.inWholeMilliseconds

    @Test
    fun `nessun treno, nessun aggiornamento, treno appena scelto, subito`() {
        assertNull(RefreshPolicy.nextRefreshAt(WatchSnapshot(), at("12:00"), foreground = true))
        assertEquals(
            at("12:00"),
            RefreshPolicy.nextRefreshAt(WatchSnapshot(selection), at("12:00"), foreground = false)
        )
    }

    @Test
    fun `fuori fascia in background si riparte all'inizio della fascia`() {
        val now = at("12:00")
        assertEquals(
            at("17:48"),
            RefreshPolicy.nextRefreshAt(snapshot(at("11:00")), now, foreground = false)
        )
        // Con l'app aperta ogni tanto, ma non oltre l'inizio della fascia.
        assertEquals(
            at("11:10"),
            RefreshPolicy.nextRefreshAt(snapshot(at("11:00")), now, foreground = true)
        )
        assertEquals(
            at("17:48"),
            RefreshPolicy.nextRefreshAt(snapshot(at("17:45")), at("17:46"), foreground = true)
        )
    }

    @Test
    fun `in fascia, più spesso vicino alla partenza`() {
        val far = at("18:18")
        assertEquals(
            far + 10.minutes,
            RefreshPolicy.nextRefreshAt(snapshot(far), far, foreground = false)
        )
        assertEquals(
            far + 3.minutes,
            RefreshPolicy.nextRefreshAt(snapshot(far), far, foreground = true)
        )
        val near = at("19:08")
        val onBoard = snapshot(near, WatchStatus.OnBoard, train)
        assertEquals(
            near + 3.minutes,
            RefreshPolicy.nextRefreshAt(onBoard, near, foreground = false)
        )
        assertEquals(
            near + 1.minutes,
            RefreshPolicy.nextRefreshAt(onBoard, near, foreground = true)
        )
    }

    @Test
    fun `il ritardo allunga la fascia`() {
        val late = snapshot(at("19:40"), WatchStatus.OnBoard, train.copy(delayMinutes = 30))
        assertEquals(
            at("19:43"),
            RefreshPolicy.nextRefreshAt(late, at("19:41"), foreground = false)
        )
    }

    @Test
    fun `treno partito, si riprende domani`() {
        val gone = snapshot(at("19:20"), WatchStatus.NotOnBoard, train)
        assertEquals(
            at("17:48", day = 27),
            RefreshPolicy.nextRefreshAt(gone, at("19:21"), foreground = false)
        )
        // Prima dell'orario "non in tabellone" vuol dire solo che non c'è ancora.
        val notYet = snapshot(at("18:00"), WatchStatus.NotOnBoard, train)
        assertEquals(
            at("18:10"),
            RefreshPolicy.nextRefreshAt(notYet, at("18:01"), foreground = false)
        )
    }

    @Test
    fun `ancora in tabellone dopo la fascia, si continua`() {
        val shown = snapshot(at("19:45"), WatchStatus.OnBoard, train)
        assertEquals(
            at("19:48"),
            RefreshPolicy.nextRefreshAt(shown, at("19:46"), foreground = false)
        )
    }

    @Test
    fun `treno dopo mezzanotte`() {
        val night = selection.copy(time = "00:10")
        val waiting = WatchSnapshot(night, null, WatchStatus.NotOnBoard, at("23:30"))
        assertEquals(
            at("23:40"),
            RefreshPolicy.nextRefreshAt(waiting, at("23:31"), foreground = false)
        )
        val onBoard = WatchSnapshot(
            night,
            train.copy(time = "00:10"),
            WatchStatus.OnBoard,
            at("00:05", day = 27)
        )
        assertEquals(
            at("00:08", day = 27),
            RefreshPolicy.nextRefreshAt(onBoard, at("00:06", day = 27), foreground = false)
        )
    }

    @Test
    fun `orario ignoto, di rado`() {
        val unknown =
            WatchSnapshot(selection.copy(time = null), null, WatchStatus.NotOnBoard, at("12:00"))
        assertEquals(
            at("12:30"),
            RefreshPolicy.nextRefreshAt(unknown, at("12:01"), foreground = true)
        )
    }

    @Test
    fun `senza connessione l'attesa raddoppia fino a 40 minuti`() {
        val good = at("18:00")
        val offline =
            snapshot(good).copy(offline = WatchOffline.NoNetwork, attemptedAt = good + 30.minutes)
        assertEquals(
            good + 60.minutes,
            RefreshPolicy.nextRefreshAt(offline, good + 31.minutes, foreground = false)
        )
        val longOffline = offline.copy(attemptedAt = good + 60.minutes)
        assertEquals(
            good + 100.minutes,
            RefreshPolicy.nextRefreshAt(longOffline, good + 61.minutes, foreground = false)
        )
        val error = snapshot(at("19:10"), WatchStatus.Error)
        assertEquals(
            at("19:20"),
            RefreshPolicy.nextRefreshAt(error, at("19:11"), foreground = true)
        )
    }
}
