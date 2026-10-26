package com.takaotech.departureboard.wear.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.watch.WatchOffline
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Internet del watch stesso (Wi-Fi o LTE), per quando il telefono non è raggiungibile.
 *
 * Una radio accesa non basta: la rete deve essere validata dal sistema, non chiedere un accesso
 * (portale) e rispondere davvero a una richiesta verso il fornitore. Tutto il download passa poi
 * da quella stessa [Network].
 */
class WatchNetwork(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Com'è andata: [Online] con il risultato di `block`, o [Offline] con il motivo. */
    sealed interface Outcome<out T> {
        class Online<T>(val value: T) : Outcome<T>
        class Offline(val reason: WatchOffline) : Outcome<Nothing>
    }

    /**
     * Chiede una rete Wi-Fi o LTE, controlla che arrivi davvero a [probeUrl] e solo allora esegue
     * [block] con un [HttpClient] legato a quella rete. La rete si rilascia alla fine, così la
     * radio non resta accesa.
     */
    suspend fun <T> withInternet(probeUrl: String, block: suspend (HttpClient) -> T): Outcome<T> {
        val updates = Channel<Pair<Network, NetworkCapabilities>>(Channel.UNLIMITED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updates.trySend(network to capabilities)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Più trasporti valgono come "uno qualsiasi": non il Bluetooth del telefono.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            connectivity.requestNetwork(request, callback)
        } catch (e: RuntimeException) {
            DebugLog.d(TAG) { "Richiesta di rete rifiutata (${e.message})" }
            return Outcome.Offline(WatchOffline.NoNetwork)
        }
        try {
            var last: NetworkCapabilities? = null
            val validated = withTimeoutOrNull(NETWORK_TIMEOUT) {
                for ((network, capabilities) in updates) {
                    last = capabilities
                    if (capabilities.isUsable()) return@withTimeoutOrNull network
                }
                null
            }
            val okHttp = validated?.let(::boundClient)
            val probe = okHttp?.let { probe(it, probeUrl) }
            val verdict = networkVerdict(
                found = last != null,
                validated = last?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                captivePortal = last?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                probeOk = probe,
            )
            DebugLog.d(TAG) { "Rete del watch: $verdict" }
            if (verdict != null || okHttp == null) return Outcome.Offline(verdict ?: WatchOffline.NoInternet)
            return HttpClient(OkHttp) { engine { preconfigured = okHttp } }.use { http ->
                Outcome.Online(block(http))
            }
        } finally {
            connectivity.unregisterNetworkCallback(callback)
            updates.close()
        }
    }

    /** OkHttp che apre i socket e risolve i nomi solo su [network]. */
    private fun boundClient(network: Network) = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns { host -> network.getAllByName(host).toList() }
        .build()

    /** Una risposta HTTP qualsiasi da [url] entro [PROBE_TIMEOUT]: errori di rete e timeout no. */
    private suspend fun probe(client: OkHttpClient, url: String): Boolean = withContext(Dispatchers.IO) {
        val quick = client.newBuilder().callTimeout(PROBE_TIMEOUT.toJavaDuration()).build()
        try {
            quick.newCall(Request.Builder().url(url).head().build()).execute().use { true }
        } catch (e: IOException) {
            DebugLog.d(TAG) { "Prova di $url fallita (${e.message})" }
            false
        }
    }

    private fun NetworkCapabilities.isUsable() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

    companion object {
        const val TAG = "ReteWatch"

        /** Quanto attendere che Wi-Fi o LTE si accendano e passino la validazione. */
        val NETWORK_TIMEOUT = 10.seconds

        /** Quanto attendere la risposta della prova. */
        val PROBE_TIMEOUT = 5.seconds
    }
}

/**
 * Perché la rete non va bene, o null se va: [found] = il sistema ha dato una rete Wi-Fi/LTE,
 * [validated] e [captivePortal] le sue capability, [probeOk] l'esito della richiesta di prova
 * (null se non è partita).
 */
internal fun networkVerdict(
    found: Boolean,
    validated: Boolean,
    captivePortal: Boolean,
    probeOk: Boolean?,
): WatchOffline? = when {
    !found -> WatchOffline.NoNetwork
    !validated || captivePortal -> WatchOffline.NoInternet
    probeOk != true -> WatchOffline.NoInternet
    else -> null
}
