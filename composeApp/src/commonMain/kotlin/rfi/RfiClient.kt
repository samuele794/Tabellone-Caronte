package rfi

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Scarica le pagine del monitor RFI. L'engine Ktor è quello della piattaforma (CIO su desktop).
 *
 * [baseUrl] è configurabile perché in un browser serve un proxy: iechub.rfi.it non manda
 * intestazioni CORS.
 */
class RfiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = HttpClient(),
) : AutoCloseable {

    private val http = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            headers.append(HttpHeaders.UserAgent, USER_AGENT)
            headers.append(HttpHeaders.AcceptLanguage, "it")
        }
    }

    /** Tutte le località (circa 2400). */
    suspend fun stations(): List<Station> =
        RfiParser.parseStations(fetch("$baseUrl/arrivipartenze"))

    suspend fun board(placeId: Int, mode: BoardMode): RfiBoard =
        RfiParser.parseBoard(
            fetch("$baseUrl/ArriviPartenze/ArrivalsDepartures/Monitor") {
                parameter("placeId", placeId)
                parameter("arrivals", mode.arrivalsParam)
            },
            mode,
        )

    private suspend fun fetch(
        url: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): String {
        val response = http.get(url, block)
        check(response.status.isSuccess()) { "RFI ha risposto ${response.status} per $url" }
        return response.bodyAsText()
    }

    override fun close() {
        http.close()
        httpClient.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://iechub.rfi.it"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:157.0) Gecko/20100101 Firefox/157.0"
    }
}
