package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.live.BoardSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Fixtures
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.RfiParser
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TrainTrackerTest {

    private val bologna = Station("837", "BOLOGNA CENTRALE")
    private val now = Instant.parse("2026-10-24T17:10:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    private val vignola = RfiTrain(
        carrier = "TRENITALIA TPER",
        carrierLogoBase64 = null,
        category = "REG",
        number = "91831 ",
        station = "VIGNOLA",
        time = "19:18",
        delayMinutes = 5,
        platform = "7 OVEST",
        flashing = true,
        info = "FERMA A: CASALECCHIO DI RENO",
        notes = listOf("ATTENZIONE! E' SEVERAMENTE VIETATO ATTRAVERSARE I BINARI"),
    )

    private val milano = RfiTrain(
        carrier = "TRENITALIA",
        carrierLogoBase64 = null,
        category = "INTERCITY NOTTE",
        number = "752",
        station = "MILANO C.LE",
        time = "06:20",
        delayMinutes = null,
        platform = "",
        flashing = false,
        info = "",
        cancelled = true,
        delayText = "Cancellato",
    )

    private class FakeSource(private val board: () -> RfiBoard) : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1", "X")
        val requests = mutableListOf<Pair<Station, BoardMode>>()

        override suspend fun stations() = emptyList<Station>()
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
            requests += station to mode
            return board()
        }

        override fun close() = Unit
    }

    private fun tracker(source: FakeSource) = TrainTracker(mapOf(StationSource.Rfi to source), clock)

    private fun boardOf(vararg trains: RfiTrain) =
        RfiBoard(bologna.name, BoardMode.Departures, trains.toList(), emptyList())

    private val selection = WatchSelection(StationSource.Rfi, bologna, "91831", "R 91831 → VIGNOLA")

    @Test
    fun `il treno in tabellone arriva con ritardo, binario e informazioni`() = runBlocking {
        val source = FakeSource { boardOf(milano, vignola) }

        val snapshot = tracker(source).track(selection)

        assertEquals(listOf(bologna to BoardMode.Departures), source.requests)
        assertEquals(WatchStatus.OnBoard, snapshot.status)
        assertEquals(now.toEpochMilliseconds(), snapshot.updatedAt)
        assertEquals(selection, snapshot.selection)
        val train = assertNotNull(snapshot.train)
        assertEquals("R", train.category)
        assertEquals("91831", train.number)
        assertEquals("VIGNOLA", train.destination)
        assertEquals("19:18", train.time)
        assertEquals(5, train.delayMinutes)
        assertEquals("7 OVEST", train.platform)
        assertTrue(train.flashing)
        assertEquals(
            "FERMA A: CASALECCHIO DI RENO - ATTENZIONE! E' SEVERAMENTE VIETATO ATTRAVERSARE I BINARI",
            train.info,
        )
        assertEquals("BOLOGNA CENTRALE", train.stationName)
    }

    @Test
    fun `un treno che non è in tabellone è in attesa`() = runBlocking {
        val snapshot = tracker(FakeSource { boardOf(milano) }).track(selection)

        assertEquals(WatchStatus.NotOnBoard, snapshot.status)
        assertNull(snapshot.train)
        assertNull(snapshot.error)
    }

    @Test
    fun `un errore del monitor finisce nello snapshot`() = runBlocking {
        val snapshot = tracker(FakeSource { error("RFI ha risposto 503") }).track(selection)

        assertEquals(WatchStatus.Error, snapshot.status)
        assertEquals("RFI ha risposto 503", snapshot.error)
        assertEquals(selection, snapshot.selection)
    }

    @Test
    fun `un fornitore che non c'è è un errore`() = runBlocking {
        val snapshot = tracker(FakeSource { boardOf(vignola) })
            .track(selection.copy(source = StationSource.Fnm))

        assertEquals(WatchStatus.Error, snapshot.status)
    }

    @Test
    fun `i candidati sono le partenze nell'ordine del monitor`() = runBlocking {
        val source = FakeSource { RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures) }

        val candidates = tracker(source).candidates(StationSource.Rfi, bologna)

        assertEquals(listOf("1247A", "1911A", "2349A"), candidates.map { it.number })
        assertEquals("BUS", candidates.first().category)
        assertEquals("BERGAMO", candidates.first().destination)
        val selection = WatchSelection.of(StationSource.Rfi, bologna, candidates.first())
        assertEquals("BUS 1247A → BERGAMO", selection.label)
        assertEquals("1247A", selection.trainNumber)
    }

    @Test
    fun `un treno cancellato resta in tabellone come cancellato`() = runBlocking {
        val snapshot = tracker(FakeSource { boardOf(milano) })
            .track(selection.copy(trainNumber = "752"))

        val train = assertNotNull(snapshot.train)
        assertTrue(train.cancelled)
        assertEquals("ICN", train.category)
        assertEquals("Cancellato", train.delayText)
    }
}
