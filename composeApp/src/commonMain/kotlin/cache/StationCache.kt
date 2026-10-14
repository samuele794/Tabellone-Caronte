package cache

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import debuglog.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import rfi.Station
import kotlin.time.Clock
import kotlin.time.Instant

/** Da dove arriva un elenco stazioni: ognuna ha la sua voce nella cache. */
enum class StationSource { Rfi }

/**
 * Elenchi stazioni salvati in locale con DataStore, in un file JSON su [path] (assoluto).
 * Un elenco vale un mese dallo scaricamento; poi si riscarica al primo uso.
 *
 * Un solo [StationCache] per file alla volta: DataStore rifiuta due istanze sullo stesso file.
 */
class StationCache(
    path: String,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : AutoCloseable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val store = DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, StationCacheSerializer) { path.toPath() },
        corruptionHandler = ReplaceFileCorruptionHandler { StationCacheData() },
        scope = scope,
    )

    /**
     * Elenco di [source]: dalla cache se non è scaduto, altrimenti da [fetch], salvandolo.
     * Se [fetch] fallisce ma c'è un elenco scaduto, meglio quello che niente.
     */
    suspend fun stations(source: StationSource, fetch: suspend () -> List<Station>): List<Station> {
        val cached = store.data.first().sources[source]
        val now = clock.now()
        if (cached != null && now < expiry(cached)) return cached.stations

        val fresh = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) throw e
            DebugLog.d(TAG) { "$source: download fallito (${e.message}), uso l'elenco scaduto" }
            return cached.stations
        }
        // Un elenco vuoto è quasi certamente una pagina cambiata: non va tenuto per un mese.
        if (fresh.isNotEmpty()) save(source, CachedStations(now.toEpochMilliseconds(), fresh))
        return fresh
    }

    private fun expiry(cached: CachedStations): Instant =
        Instant.fromEpochMilliseconds(cached.downloadedAt).plus(VALIDITY, timeZone)

    private suspend fun save(source: StationSource, entry: CachedStations) {
        try {
            store.updateData { it.copy(sources = it.sources + (source to entry)) }
        } catch (e: IOException) {
            // L'elenco scaricato resta valido anche se non si riesce a salvarlo.
            DebugLog.d(TAG) { "$source: salvataggio fallito (${e.message})" }
        }
    }

    override fun close() = scope.cancel()

    companion object {
        const val TAG = "CacheStazioni"
        private val VALIDITY = DateTimePeriod(months = 1)
    }
}

@Serializable
internal data class CachedStations(
    /** Istante dello scaricamento, in millisecondi dall'epoch. */
    val downloadedAt: Long,
    val stations: List<Station>,
)

@Serializable
internal data class StationCacheData(
    val sources: Map<StationSource, CachedStations> = emptyMap(),
)

private object StationCacheSerializer : OkioSerializer<StationCacheData> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = StationCacheData()

    override suspend fun readFrom(source: BufferedSource): StationCacheData =
        try {
            json.decodeFromString(StationCacheData.serializer(), source.readUtf8())
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cache stazioni illeggibile", e)
        }

    override suspend fun writeTo(t: StationCacheData, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(StationCacheData.serializer(), t))
    }
}
