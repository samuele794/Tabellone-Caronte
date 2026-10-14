package fnm

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import debuglog.DebugLog
import fnm.FnmKeyStore.Companion.RETRY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Chiave delle risposte del BFF Trenord, salvata con DataStore in un file JSON su [path].
 *
 * La chiave è una frase scritta nel JavaScript del sito ([fetchJs]). Se Trenord la cambia, la
 * prima risposta che non si decifra fa riscaricare il JavaScript: tra i letterali candidati
 * ([FnmParser.keyCandidates]) vale quello che decifra la risposta in un JSON valido, e da lì in
 * poi si usa quello, anche dopo un riavvio. Il JavaScript si riscarica al più ogni [RETRY].
 *
 * Un solo [FnmKeyStore] per file alla volta: DataStore rifiuta due istanze sullo stesso file.
 */
class FnmKeyStore(
    path: String,
    private val fetchJs: suspend () -> String,
    private val clock: Clock = Clock.System,
    /** Chiave da provare finché non se ne trova una più recente. */
    private val defaultPassphrase: String = DEFAULT_PASSPHRASE,
) : AutoCloseable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val store = DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, KeySerializer) { path.toPath() },
        corruptionHandler = ReplaceFileCorruptionHandler { KeyData() },
        scope = scope,
    )

    private val refresh = Mutex()
    private var lastRefresh: Instant? = null

    /** Il JSON in chiaro di [body]; se nessuna chiave funziona lancia [IllegalStateException]. */
    suspend fun decrypt(body: ByteArray): String {
        decryptWith(passphrase(), body)?.let { return it }
        return refresh.withLock {
            // Nel frattempo un'altra richiesta può aver già trovato la chiave nuova.
            decryptWith(passphrase(), body)?.let { return@withLock it }
            val last = lastRefresh
            val now = clock.now()
            check(last == null || now - last >= RETRY) { "Chiave Trenord non valida" }
            lastRefresh = now
            val candidates = FnmParser.keyCandidates(fetchJs())
            for (candidate in candidates) {
                val text = decryptWith(candidate, body) ?: continue
                DebugLog.d(TAG) { "Nuova chiave del BFF ricavata dal JavaScript (${candidates.size} candidati)" }
                save(candidate)
                return@withLock text
            }
            error("Chiave Trenord non trovata tra ${candidates.size} candidati")
        }
    }

    private suspend fun passphrase(): String = store.data.first().passphrase ?: defaultPassphrase

    /** Testo in chiaro se [passphrase] decifra [body] in un elenco JSON. */
    private fun decryptWith(passphrase: String, body: ByteArray): String? {
        val text = FnmCrypto.decrypt(body, passphrase) ?: return null
        val valid = try {
            Json.parseToJsonElement(text) is JsonArray
        } catch (e: IllegalArgumentException) {
            false
        }
        return text.takeIf { valid }
    }

    private suspend fun save(passphrase: String) {
        try {
            store.updateData { KeyData(passphrase, clock.now().toEpochMilliseconds()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // La chiave resta buona per questa sessione anche se non si riesce a salvarla.
            DebugLog.d(TAG) { "Salvataggio della chiave fallito (${e.message})" }
        }
    }

    override fun close() = scope.cancel()

    companion object {
        const val TAG = "ChiaveFnm"

        /** La chiave in uso a ottobre 2026. */
        const val DEFAULT_PASSPHRASE = "8hI&WK=1NQ55*f^yyZkdEGWYyN{S"

        /** Intervallo minimo tra due download del JavaScript. */
        val RETRY = 10.minutes
    }
}

@Serializable
internal data class KeyData(
    val passphrase: String? = null,
    /** Quando è stata trovata, in millisecondi dall'epoch. */
    val foundAt: Long = 0,
)

private object KeySerializer : OkioSerializer<KeyData> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = KeyData()

    override suspend fun readFrom(source: BufferedSource): KeyData =
        try {
            json.decodeFromString(KeyData.serializer(), source.readUtf8())
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Chiave Trenord illeggibile", e)
        }

    override suspend fun writeTo(t: KeyData, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(KeyData.serializer(), t))
    }
}
