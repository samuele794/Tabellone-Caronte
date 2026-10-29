package com.takaotech.departureboard.live

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.takaotech.departureboard.MapCamera
import com.takaotech.departureboard.MapStation
import com.takaotech.departureboard.announce.board
import com.takaotech.departureboard.announce.train
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MapBoardTest {

    private val position = LatLng(45.486347, 9.204528)

    /** Tabellone che mostra [trains] e ricorda cosa gli è stato chiesto. */
    private class FakeSource(
        override val source: StationSource,
        var trains: List<RfiTrain> = emptyList(),
    ) : BoardSource {
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        val requests = mutableListOf<Pair<String, BoardMode>>()
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
            requests += station.id to mode
            return board(*trains.toTypedArray(), mode = mode)
        }

        override fun close() = Unit
    }

    private val rfi = FakeSource(StationSource.Rfi)
    private val fnm = FakeSource(StationSource.Fnm)
    private val state = LiveBoardState(mapOf(StationSource.Rfi to rfi, StationSource.Fnm to fnm))

    private val centrale = MapStation(
        "MILANO CENTRALE",
        position,
        mapOf(
            StationSource.Rfi to Station("1728", "MILANO CENTRALE", position),
            StationSource.Fnm to Station("S01700", "MILANO CENTRALE", position),
        ),
    )

    @Test
    fun `la scheda mostra il tabellone del primo fornitore, senza toccare quello principale`() =
        runTest {
            val board = state.mapBoard(centrale)
            board.feed.refresh()
            board.mode = BoardMode.Arrivals
            board.feed.refresh()

            assertEquals(StationSource.Rfi, board.source)
            assertEquals(
                listOf("1728" to BoardMode.Departures, "1728" to BoardMode.Arrivals),
                rfi.requests
            )
            assertEquals(emptyList(), fnm.requests)
            assertNull(state.updatedAt)
        }

    @Test
    fun `senza annunci le righe variate lampeggiano subito, quanto scelto nelle impostazioni`() =
        runTest {
            state.announceBlink = 12.seconds
            rfi.trains = listOf(train(number = "9915"), train(number = "2162"))
            val board = state.mapBoard(centrale)
            launch { board.feed.runBlinks() }
            board.feed.refresh()
            runCurrent()
            assertEquals(
                emptySet(),
                board.feed.blinkingTrains,
                "il primo tabellone fa solo da base"
            )

            rfi.trains = listOf(train(number = "9915"), train(number = "2162", platform = "4"))
            board.feed.refresh()
            runCurrent()
            assertEquals(setOf("2162"), board.feed.blinkingTrains)
            assertEquals(
                listOf("2162"),
                board.feed.boardDepartures.filter { it.blink }.map { it.number })

            advanceTimeBy(12.seconds.inWholeMilliseconds + 1)
            assertEquals(emptySet(), board.feed.blinkingTrains)
            coroutineContext.cancelChildren()
        }

    private fun station(name: String, id: String) =
        MapStation(
            name,
            LatLng(45.0, id.length.toDouble()),
            mapOf(StationSource.Rfi to Station(id, name))
        )

    private val stations = listOf(
        centrale,
        station("ROMA TERMINI", "S08409"),
        station("NAPOLI CENTRALE", "S09218"),
        station("TORINO PORTA NUOVA", "S00219"),
    )

    @Test
    fun `oltre tre finestre non se ne apre un'altra e le aperte restano`() {
        stations.take(3).forEach { assertTrue(state.openMapBoard(it)) }

        assertFalse(state.openMapBoard(stations[3]))
        assertEquals(stations.take(3), state.mapBoards.map { it.station })

        // Riaprirne una già aperta la porta sopra le altre.
        assertTrue(state.openMapBoard(centrale))
        assertEquals(listOf(stations[1], stations[2], centrale), state.mapBoards.map { it.station })

        state.closeMapBoard(state.mapBoards.first())
        assertTrue(state.openMapBoard(stations[3]))
        assertEquals(listOf(stations[2], centrale, stations[3]), state.mapBoards.map { it.station })
    }

    @Test
    fun `le finestre si aprono a cascata`() {
        stations.take(3).forEach { state.openMapBoard(it) }
        val (first, second, third) = state.mapBoards.map { it.offset }
        assertEquals(DpOffset(first.x + 32.dp, first.y + 32.dp), second)
        assertEquals(DpOffset(first.x + 64.dp, first.y + 64.dp), third)
    }

    @Test
    fun `una rotazione riapre la mappa senza perdere finestre e inquadratura`() {
        state.openMap()
        state.openMapBoard(centrale)
        state.mapBoards.single().apply {
            mode = BoardMode.Arrivals
            width = 720.dp
            fullScreen = true
        }
        val camera = MapCamera(position, zoom = 9.0, bearing = 0.0, tilt = 0.0)
        state.mapCamera = camera

        state.openMap()

        val board = state.mapBoards.single()
        assertEquals(BoardMode.Arrivals, board.mode)
        assertEquals(720.dp, board.width)
        assertTrue(board.fullScreen)
        assertEquals(camera, state.mapCamera)

        state.closeMap()
        assertEquals(emptyList(), state.mapBoards)
        assertNull(state.mapCamera)
    }

    @Test
    fun `dalla finestra si apre il tabellone completo della stazione`() {
        state.openMapBoard(stations[1])
        val board = state.mapBoards.single()
        board.mode = BoardMode.Arrivals

        state.showOnBoard(board)

        assertEquals(StationSource.Rfi, state.source)
        assertEquals(Station("S08409", "ROMA TERMINI"), state.station)
        assertEquals(BoardMode.Arrivals, state.mode)
    }
}

