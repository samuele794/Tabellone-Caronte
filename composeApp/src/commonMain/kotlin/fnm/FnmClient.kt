package fnm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import rfi.Station

/**
 * Le chiamate a trenord.it usate dal tabellone FNM. L'engine Ktor è quello della piattaforma.
 *
 * - elenco stazioni: JSON;
 * - treni di una stazione (`station-details`): HTML dentro JSON, lento e pesante;
 * - dettaglio treno dal BFF: JSON cifrato (vedi [FnmKeyStore]);
 * - dettaglio treno HTML (`train-sub-detail`): ripiego senza binari;
 * - il JavaScript del sito, da cui si ricava la chiave del BFF.
 */
class FnmClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = HttpClient(),
    /** null per non mandarlo: nel browser non si può impostare, lo aggiunge il proxy. */
    private val userAgent: String? = USER_AGENT,
) : AutoCloseable {

    private val http = httpClient.config {
        install(HttpTimeout) {
            // station-details può impiegare più di 20 s.
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            userAgent?.let { headers.append(HttpHeaders.UserAgent, it) }
            headers.append(HttpHeaders.AcceptLanguage, "it")
        }
    }

    /** Circa 560 stazioni, codice MIR come id ("S01067"). */
    suspend fun stations(): List<Station> =
        FnmParser.parseStations(
            fetch("$baseUrl/mia/v2/stazioni_v2/") {
                parameter("ignore_during_search", "false")
                parameter("_p", "NomeGeoStazioni,CodiceMIR")
                parameter("_s", "NomeGeoStazioni")
            }.bodyAsText()
        )

    /** Partenze e arrivi delle prossime due ore circa, solo con l'orario programmato. */
    suspend fun stationDetails(mir: String): FnmStationLists =
        FnmParser.parseStationDetails(
            fetch("$baseUrl/rest/render/station-details") {
                parameter("mirCode", mir)
                parameter("mxp", "false")
                parameter("L", "0")
            }.bodyAsText()
        )

    /** Corpo cifrato: tutti i treni con numero [number] che circolano il giorno [date]. */
    suspend fun trainBff(number: String, date: LocalDate): ByteArray =
        fetch("$baseUrl/mia/bff/train/$number") { parameter("date", date.toString()) }.bodyAsBytes()

    /** Codice trasporto → HTML del dettaglio, per tutti i treni con numero [number]. */
    suspend fun trainSubDetails(number: String): Map<String, String> =
        FnmParser.parseSubDetails(
            fetch("$baseUrl/rest/render/train-sub-detail") {
                parameter(
                    "trainId",
                    number
                )
            }.bodyAsText()
        )

    suspend fun webComponentsJs(): String = fetch("$baseUrl$WEB_COMPONENTS_PATH").bodyAsText()

    private suspend fun fetch(
        url: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        val response = http.get(url, block)
        check(response.status.isSuccess()) { "Trenord ha risposto ${response.status} per $url" }
        return response
    }

    override fun close() {
        http.close()
        httpClient.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.trenord.it"
        const val WEB_COMPONENTS_PATH =
            "/fileadmin/templates/js/web-components/elements/trenord-web-components.js"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:157.0) Gecko/20100101 Firefox/157.0"
    }
}
