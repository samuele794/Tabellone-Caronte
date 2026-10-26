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

    private class FakeSource(private val board: (Station) -> RfiBoard) : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1", "X")
        val requests = mutableListOf<Pair<Station, BoardMode>>()

        override suspend fun stations() = emptyList<Station>()
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
            requests += station to mode
            return board(station)
        }

        override fun close() = Unit
    }

    private fun tracker(source: FakeSource) = TrainTracker(mapOf(StationSource.Rfi to source), clock)

    /** Un treno solo, mai controllato prima. */
    private suspend fun TrainTracker.track(selection: WatchSelection) =
        track(listOf(selection), WatchSnapshot())

    private fun boardOf(vararg trains: RfiTrain) =
        RfiBoard(bologna.name, BoardMode.Departures, trains.toList(), emptyList())

    private val selection =
        WatchSelection(StationSource.Rfi, bologna, "91831", "R 91831 → VIGNOLA", "19:18")

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
    fun `un treno scelto per numero prende etichetta e orario quando compare`() = runBlocking {
        val chosen = WatchSelection.byNumber(StationSource.Rfi, bologna, "91831")

        val waiting = tracker(FakeSource { boardOf(milano) }).track(chosen)
        assertEquals(chosen, waiting.selection)

        val seen = tracker(FakeSource { boardOf(milano, vignola) }).track(chosen)
        assertEquals(WatchStatus.OnBoard, seen.status)
        assertEquals(selection, seen.selection)
    }

    private val modena = Station("903", "MODENA")

    /** Il 752 da Modena, alle 20:00. */
    private val evening =
        WatchSelection(StationSource.Rfi, modena, "752", "ICN 752 → MILANO C.LE", "20:00")

    @Test
    fun `partito il treno mostrato si controlla subito il successivo, anche da un'altra stazione`() =
        runBlocking {
            val source =
                FakeSource { station -> if (station == modena) boardOf(milano) else boardOf() }
            val seen = WatchSnapshot(
                selection,
                null,
                WatchStatus.OnBoard,
                now.toEpochMilliseconds() - 120_000
            )
                .planned(listOf(evening, selection), now)
            assertEquals(selection, seen.selection)

            val snapshot = tracker(source).track(listOf(evening, selection), seen)

            assertEquals(listOf(bologna, modena), source.requests.map { it.first })
            assertEquals(listOf(selection.key), snapshot.departed)
            assertEquals(
                evening.copy(label = "ICN 752 → MILANO C.LE", time = "20:00"),
                snapshot.selection
            )
            assertEquals(WatchStatus.OnBoard, snapshot.status)
            assertEquals("752", snapshot.train?.number)
        }

    @Test
    fun `un treno scelto per numero si cerca anche se non è quello mostrato, e va al suo posto`() =
        runBlocking {
            // Il 752 parte alle 06:20: trovato alle 19:10, è già passato e si resta sul 91831.
            val chosen = WatchSelection.byNumber(StationSource.Rfi, modena, "752")
            val source =
                FakeSource { station -> if (station == modena) boardOf(milano) else boardOf(vignola) }

            val snapshot = tracker(source).track(listOf(chosen, selection), WatchSnapshot())

            assertEquals("06:20", snapshot.plan.first().time)
            assertEquals(listOf(chosen.key, selection.key), snapshot.plan.map { it.key })
            assertEquals(selection, snapshot.selection)
            assertEquals(WatchStatus.OnBoard, snapshot.status)
            // Ogni monitor una volta sola.
            assertEquals(listOf(modena, bologna), source.requests.map { it.first })
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
