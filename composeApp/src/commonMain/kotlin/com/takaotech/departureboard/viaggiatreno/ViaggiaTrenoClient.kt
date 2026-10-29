package com.takaotech.departureboard.viaggiatreno

import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.Station
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Posizioni delle stazioni da ViaggiaTreno (`elencoStazioni`, vedi docs/viaggiatreno-api.md).
 * Il server risponde solo in HTTP e senza CORS: nel browser servirebbe un proxy.
 */
class ViaggiaTrenoClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    httpClient: HttpClient = HttpClient(),
) : AutoCloseable {

    private val http = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    /**
     * Tutte le stazioni con coordinate (circa 3360), una chiamata per regione. Fallisce se ne
     * fallisce una: un elenco a metà toglierebbe le posizioni di intere regioni per un mese.
     */
    suspend fun stationPositions(): StationPositions = coroutineScope {
        REGIONS.map { region -> async { fetchRegion(region) } }
            .awaitAll()
            .let { StationPositions(it.flatten()) }
    }

    private suspend fun fetchRegion(region: Int): List<VtStation> {
        val url = "$baseUrl/elencoStazioni/$region"
        val response = http.get(url)
        check(response.status.isSuccess()) { "ViaggiaTreno ha risposto ${response.status} per $url" }
        return parseStations(response.bodyAsText())
    }

    override fun close() {
        http.close()
    }

    companion object {
        const val DEFAULT_BASE_URL =
            "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"

        /** Codici regione: 0 sono le stazioni principali, 1…22 le regioni. */
        private val REGIONS = 0..22

        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseStations(body: String): List<VtStation> =
            json.decodeFromString<List<VtStation>>(body)
    }
}

/**
 * Coordinate delle stazioni ViaggiaTreno, per codice e per nome. Il codice ViaggiaTreno è lo stesso
 * MIR delle stazioni FNM; quelle RFI hanno un `placeId` diverso e si abbinano per nome (2424 su
 * 2434 coincidono).
 */
class StationPositions internal constructor(stations: List<VtStation>) {
    private val byCode = HashMap<String, LatLng>()
    private val byName = HashMap<String, LatLng>()

    init {
        // Una stazione di confine compare in più regioni: vale la prima.
        for (s in stations) {
            val position = s.position ?: continue
            byCode.getOrPut(s.codiceStazione) { position }
            s.localita?.nomeLungo?.let { byName.getOrPut(nameKey(it)) { position } }
        }
    }

    val size: Int get() = byCode.size

    fun of(station: Station): LatLng? = byCode[station.id] ?: byName[nameKey(station.name)]

    /** [stations] con la posizione, dove si trova. */
    fun locate(stations: List<Station>): List<Station> =
        stations.map { s -> of(s)?.let { s.copy(position = it) } ?: s }

    private fun nameKey(name: String) = name.trim().uppercase()
}

@Serializable
internal data class VtStation(
    val codiceStazione: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val localita: VtLocalita? = null,
) {
    /** null senza coordinate, o con 0,0 che sta per "non nota". */
    val position: LatLng?
        get() = if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) null else LatLng(
            lat,
            lon
        )
}

@Serializable
internal data class VtLocalita(val nomeLungo: String? = null)
