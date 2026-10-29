package com.takaotech.departureboard.viaggiatreno

import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.Station
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ViaggiaTrenoClientTest {

    /** Le regioni si scaricano in parallelo: il MockEngine le registra da più thread. */
    private val requests = ConcurrentLinkedQueue<String>()

    /** Voce di `elencoStazioni` come la manda il server, con i campi che non servono. */
    private fun station(code: String, name: String, lat: Double, lon: Double) = """
        {"codiceStazione":"$code","codReg":1,"tipoStazione":3,"lat":$lat,"lon":$lon,
         "localita":{"nomeLungo":"$name","nomeBreve":"$name","label":null,"id":"$code"},
         "nomeCitta":"X","esterno":false,"dettZoomStaz":[]}
    """.trimIndent()

    private val lombardia = "[" + listOf(
        station("S01700", "MILANO CENTRALE", 45.486347, 9.204528),
        station("S01066", "MILANO CADORNA", 45.468, 9.175),
        station("S09999", "POSTO DI SERVIZIO", 0.0, 0.0),
    ).joinToString(",") + "]"

    /** La stessa stazione di confine compare anche in un'altra regione, con altre coordinate. */
    private val piemonte = "[" + station("S01700", "MILANO CENTRALE", 1.0, 1.0) + "]"

    private fun client(failing: Int? = null) = ViaggiaTrenoClient(
        httpClient = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            val region = request.url.encodedPath.substringAfterLast('/').toInt()
            val body = when (region) {
                1 -> lombardia
                2 -> piemonte
                else -> "[]"
            }
            val status =
                if (region == failing) HttpStatusCode.InternalServerError else HttpStatusCode.OK
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    @Test
    fun `scarica tutte le regioni da 0 a 22`() = runBlocking {
        client().use { it.stationPositions() }

        assertEquals(
            (0..22).map { "/infomobilita/resteasy/viaggiatreno/elencoStazioni/$it" }.toSet(),
            requests.toSet(),
        )
    }

    @Test
    fun `abbina per codice MIR le FNM e per nome le RFI`() = runBlocking {
        val positions = client().use { it.stationPositions() }

        val located = positions.locate(
            listOf(
                Station("1728", "MILANO CENTRALE"),
                Station("S01066", "Milano Cadorna "),
                Station("2000", "SAN GOTTARDO"),
            ),
        )

        assertEquals(
            listOf(
                Station("1728", "MILANO CENTRALE", LatLng(45.486347, 9.204528)),
                Station("S01066", "Milano Cadorna ", LatLng(45.468, 9.175)),
                Station("2000", "SAN GOTTARDO"),
            ),
            located,
        )
    }

    @Test
    fun `una stazione in più regioni tiene la prima e 0,0 vale come senza posizione`() =
        runBlocking {
            val positions = client().use { it.stationPositions() }

            assertEquals(2, positions.size)
            assertEquals(LatLng(45.486347, 9.204528), positions.of(Station("S01700", "")))
            assertNull(positions.of(Station("S09999", "POSTO DI SERVIZIO")))
        }

    @Test
    fun `se una regione fallisce fallisce tutto`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { client(failing = 7).use { it.stationPositions() } }
    }
}
