package com.takaotech.departureboard.fnm

import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Station
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FnmBoardSourceTest {

    private val dir = Files.createTempDirectory("fnm-board").toFile()
    private val domodossola = Station(FnmFixtures.DOMODOSSOLA_MIR, "MILANO DOMODOSSOLA")

    private val clock = object : Clock {
        // 17:44 a Milano.
        var now = Instant.parse("2026-10-14T15:44:00Z")
        override fun now() = now
        fun advance(by: Duration) {
            now += by
        }
    }

    private var listBody = FnmFixtures.domodossola
    private val listed = FnmParser.parseStationDetails(FnmFixtures.domodossola)
    private var listRequests = 0
    private val listStations = mutableListOf<String>()
    private val bffRequests = mutableListOf<String>()
    private val htmlRequests = mutableListOf<String>()
    private val bffOverride = mutableMapOf<String, String>()
    private val bffDown = mutableSetOf<String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path == "/rest/render/station-details" -> {
                listRequests++
                listStations += request.url.parameters["mirCode"].orEmpty()
                respond(listBody, HttpStatusCode.OK, json)
            }

            path.startsWith("/mia/bff/train/") -> {
                val number = path.substringAfterLast('/')
                bffRequests += number
                assertEquals("2026-10-14", request.url.parameters["date"])
                val body = bffOverride[number] ?: bff(number)
                if (number in bffDown || body == null) respondError(HttpStatusCode.InternalServerError)
                else respond(BffResponses.encrypt(body), HttpStatusCode.OK)
            }

            path == "/rest/render/train-sub-detail" -> {
                val number = request.url.parameters["trainId"].orEmpty()
                htmlRequests += number
                if (number == "2162") respond(FnmFixtures.subDetail2162, HttpStatusCode.OK, json)
                else respondError(HttpStatusCode.NotFound)
            }

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private val source = FnmBoardSource(
        FnmClient(httpClient = HttpClient(engine)),
        FnmKeyStore(File(dir, "fnm-key.json").absolutePath, { FnmFixtures.webComponents }, clock),
        clock,
        TimeZone.of("Europe/Rome"),
    )

    @AfterTest
    fun cleanup() {
        source.close()
        dir.deleteRecursively()
    }

    @Test
    fun `partenze con i dettagli dei primi 15 treni`() = runBlocking {
        val board = source.board(domodossola, BoardMode.Departures)

        assertEquals("MILANO DOMODOSSOLA", board.stationName)
        assertEquals(FnmBoardSource.MAX_TRAINS, board.trains.size)
        assertEquals(FnmBoardSource.MAX_TRAINS, bffRequests.size)
        assertEquals(emptyList(), htmlRequests)
        val first = board.trains.first()
        assertEquals("2162", first.number)
        assertEquals("MILANO CADORNA", first.station)
        assertEquals("2", first.platform)
        assertEquals(4, first.delayMinutes)
        assertTrue(board.trains.all { it.platform.isNotBlank() })
    }

    @Test
    fun `arrivi con la provenienza, dallo stesso elenco`() = runBlocking {
        source.board(domodossola, BoardMode.Departures)
        val board = source.board(domodossola, BoardMode.Arrivals)

        assertEquals(1, listRequests)
        assertEquals("COMO LAGO", board.trains.first().station)
        assertEquals("17:43", board.trains.first().time)
    }

    @Test
    fun `l'elenco si riscarica solo dopo 5 minuti`() = runBlocking {
        source.board(domodossola, BoardMode.Departures)
        assertTrue(source.nextListRefreshAt(domodossola) != null)
        clock.advance(4.minutes)
        source.board(domodossola, BoardMode.Departures)
        assertEquals(1, listRequests)
        assertEquals(2 * FnmBoardSource.MAX_TRAINS, bffRequests.size)

        clock.advance(1.minutes)
        source.board(domodossola, BoardMode.Departures)
        assertEquals(2, listRequests)
    }

    @Test
    fun `l'elenco di ogni stazione resta, chiedendone altre`() = runBlocking {
        val cadorna = Station("S01066", "MILANO CADORNA")
        source.board(domodossola, BoardMode.Departures)
        source.board(cadorna, BoardMode.Departures)
        clock.advance(1.minutes)
        source.board(domodossola, BoardMode.Departures)
        source.board(cadorna, BoardMode.Arrivals)

        assertEquals(listOf(FnmFixtures.DOMODOSSOLA_MIR, "S01066"), listStations)
        // Ogni stazione ha il suo conto alla rovescia: Cadorna è stata scaricata dopo.
        assertTrue(
            assertNotNull(source.nextListRefreshAt(cadorna)) >
                    assertNotNull(source.nextListRefreshAt(domodossola))
        )
    }

    @Test
    fun `invalidando l'elenco si riscarica subito, con i dettagli`() = runBlocking {
        source.board(domodossola, BoardMode.Departures)
        val before = assertNotNull(source.nextListRefreshAt(domodossola))
        clock.advance(1.minutes)

        source.invalidateList()
        source.board(domodossola, BoardMode.Departures)

        assertEquals(2, listRequests)
        assertEquals(2 * FnmBoardSource.MAX_TRAINS, bffRequests.size)
        assertTrue(assertNotNull(source.nextListRefreshAt(domodossola)) > before)
    }

    @Test
    fun `l'intervallo dell'elenco si può cambiare`() = runBlocking {
        source.listRefresh = 2.minutes
        source.board(domodossola, BoardMode.Departures)
        clock.advance(1.minutes)
        source.board(domodossola, BoardMode.Departures)
        assertEquals(1, listRequests)

        clock.advance(1.minutes)
        source.board(domodossola, BoardMode.Departures)
        assertEquals(2, listRequests)
    }

    @Test
    fun `cambiando intervallo si sposta il prossimo riscaricamento dell'elenco`() = runBlocking {
        source.board(domodossola, BoardMode.Departures)
        val before = assertNotNull(source.nextListRefreshAt(domodossola))

        source.listRefresh = 10.minutes

        assertEquals(5.minutes, assertNotNull(source.nextListRefreshAt(domodossola)) - before)
    }

    @Test
    fun `se il bff non risponde si usa il dettaglio html`() = runBlocking {
        bffDown += "2162"

        val first = source.board(domodossola, BoardMode.Departures).trains.first()

        assertEquals(listOf("2162"), htmlRequests)
        assertEquals("2162", first.number)
        assertEquals("", first.platform)
        assertEquals(3, first.delayMinutes)
    }

    @Test
    fun `treno tolto dall'elenco resta finche non parte`() = runBlocking {
        source.board(domodossola, BoardMode.Departures)

        // Nuovo elenco senza il 2162, che però secondo il BFF non è ancora partito.
        listBody =
            FnmFixtures.stationDetails(FnmFixtures.domodossolaTrains.filter { it.number != "2162" })
        clock.advance(5.minutes)
        assertTrue(
            source.board(
                domodossola,
                BoardMode.Departures
            ).trains.any { it.number == "2162" })

        bffOverride["2162"] = BffResponses.train2162(departedFromDomodossola = true)
        clock.advance(1.minutes)
        val trains = source.board(domodossola, BoardMode.Departures).trains
        assertTrue(trains.none { it.number == "2162" })
        assertEquals(FnmBoardSource.MAX_TRAINS, trains.size)
    }

    /** Il 2162 com'era alle 17:44; gli altri treni dell'elenco in orario, binario 1. */
    private fun bff(number: String): String? {
        if (number == "2162") return BffResponses.train2162()
        val row = listed.departures.firstOrNull { it.number == number } ?: return null
        return BffResponses.passingThrough(
            row,
            listed.arrivals.firstOrNull { it.code == row.code }?.time
        )
    }

    private companion object {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
