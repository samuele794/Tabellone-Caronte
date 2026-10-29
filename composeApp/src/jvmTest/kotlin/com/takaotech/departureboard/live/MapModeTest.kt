package com.takaotech.departureboard.live

import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class MapModeTest {

    private val milanoPosition = LatLng(45.486347, 9.204528)

    private class FakeSource(
        override val source: StationSource,
        private val stations: List<Station>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : BoardSource {
        override val defaultStation = stations.first()
        var invalidations = 0
        override suspend fun stations() = stations
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
            gate?.await()
            return RfiBoard(station.name, mode, emptyList(), listOf("Avviso"))
        }

        override fun invalidateList() {
            invalidations++
        }

        override fun close() = Unit
    }

    private val rfi = FakeSource(StationSource.Rfi, listOf(Station("1728", "MILANO CENTRALE")))

    @Test
    fun `aprendo la mappa i dati del tabellone si buttano`() = runBlocking {
        val state = LiveBoardState(mapOf(StationSource.Rfi to rfi))
        state.refresh()
        assertNotNull(state.updatedAt)
        assertTrue(state.nextRefreshIn(10.minutes) > Duration.ZERO)

        state.openMap()

        assertTrue(state.showMap)
        assertEquals(emptyList(), state.notices)
        assertNull(state.updatedAt)
        // Chiudendo la mappa il tabellone si riscarica subito, elenco treni compreso.
        assertEquals(Duration.ZERO, state.nextRefreshIn(10.minutes))
        assertEquals(1, rfi.invalidations)
    }

    @Test
    fun `un tabellone arrivato mentre si apre la mappa si scarta`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = FakeSource(StationSource.Rfi, listOf(Station("1728", "MILANO CENTRALE")), gate)
        val state = LiveBoardState(mapOf(StationSource.Rfi to slow))
        val refresh = launch { state.refresh() }
        withTimeout(5_000) { while (!state.loading) yield() }

        state.openMap()
        gate.complete(Unit)
        refresh.join()

        assertEquals(emptyList(), state.notices)
        assertNull(state.updatedAt)
        state.closeMap()
        assertEquals(Duration.ZERO, state.nextRefreshIn(10.minutes))
    }

    @Test
    fun `la mappa ha le stazioni di tutti i fornitori, una per posizione`() = runBlocking {
        val rfi = FakeSource(
            StationSource.Rfi,
            listOf(
                Station("1728", "MILANO CENTRALE", milanoPosition),
                Station("2000", "SAN GOTTARDO"),
            ),
        )
        val fnm = FakeSource(
            StationSource.Fnm,
            listOf(
                Station("S01700", "MILANO CENTRALE", milanoPosition),
                Station("S01066", "MILANO CADORNA", LatLng(45.468, 9.175)),
            ),
        )
        val state = LiveBoardState(mapOf(StationSource.Rfi to rfi, StationSource.Fnm to fnm))

        state.loadMapStations()

        assertEquals(
            listOf(
                MapStation(
                    "MILANO CENTRALE",
                    milanoPosition,
                    listOf(StationSource.Rfi, StationSource.Fnm)
                ),
                MapStation("MILANO CADORNA", LatLng(45.468, 9.175), listOf(StationSource.Fnm)),
            ),
            state.mapStations,
        )
    }

    @Test
    fun `la ricerca mette prima i nomi che iniziano col testo, poi quelli che lo contengono`() {
        fun station(name: String) = MapStation(name, LatLng(0.0, 0.0), listOf(StationSource.Rfi))
        val stations = listOf(
            station("SAN MILANO"),
            station("MILANO ROGOREDO"),
            station("BOLOGNA CENTRALE"),
            station("MILANO CENTRALE"),
        )

        assertEquals(
            listOf("MILANO CENTRALE", "MILANO ROGOREDO", "SAN MILANO"),
            stations.searchStations(" milano ").map { it.name },
        )
        assertEquals(
            listOf("MILANO CENTRALE"),
            stations.searchStations("milano", limit = 1).map { it.name })
        assertEquals(emptyList(), stations.searchStations("  "))
    }
}
