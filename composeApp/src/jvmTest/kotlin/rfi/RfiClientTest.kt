package rfi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RfiClientTest {

    private val requests = mutableListOf<Url>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK) = RfiClient(
        httpClient = HttpClient(MockEngine { request ->
            requests += request.url
            val body = when {
                request.url.encodedPath.endsWith("/arrivipartenze") -> Fixtures.stazioni
                request.url.parameters["arrivals"] == "True" -> Fixtures.napoliArrivi
                else -> Fixtures.milanoPartenze
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
        }),
    )

    @Test
    fun `url del monitor per partenze e arrivi`() = runBlocking {
        client().use { c ->
            assertEquals("MILANO CENTRALE", c.board("1728", BoardMode.Departures).stationName)
            assertEquals("NAPOLI CENTRALE", c.board("1888", BoardMode.Arrivals).stationName)
        }

        assertEquals(
            listOf(
                "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=1728&arrivals=False",
                "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=1888&arrivals=True",
            ),
            requests.map { it.toString() },
        )
    }

    @Test
    fun `elenco stazioni`() = runBlocking {
        val stations = client().use { it.stations() }

        assertEquals(2435, stations.size)
        assertEquals("https://iechub.rfi.it/arrivipartenze", requests.single().toString())
    }

    @Test
    fun `errore http`() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                client(HttpStatusCode.ServiceUnavailable).use {
                    it.board(
                        "1728",
                        BoardMode.Departures
                    )
                }
            }
        }
    }
}
