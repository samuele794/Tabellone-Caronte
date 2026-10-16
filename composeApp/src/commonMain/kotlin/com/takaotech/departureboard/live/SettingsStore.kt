package com.takaotech.departureboard.live

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.cache.blockingClose
import com.takaotech.departureboard.cache.ioDispatcher
import com.takaotech.departureboard.cache.jsonDataStore
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/** Fornitore e stazione scelti per il tabellone. */
@Serializable
data class Selection(val source: StationSource, val station: Station)

/**
 * Impostazioni del tabellone che si ritrovano alla riapertura dell'app. Un campo nuovo va
 * aggiunto con un valore predefinito, così i file salvati prima continuano a leggersi.
 */
@Serializable
data class BoardSettings(
    /** null finché non si è mai scelta una stazione. */
    val selection: Selection? = null,
    val autoScroll: Boolean = true,
    val announce: Boolean = true,
    /** Secondi tra un aggiornamento e l'altro, per fornitore; chi manca usa il predefinito. */
    val refreshSeconds: Map<StationSource, Int> = emptyMap(),
    /** Secondi tra un riscaricamento dell'elenco treni e l'altro, per i fornitori che lo fanno a parte. */
    val listRefreshSeconds: Map<StationSource, Int> = emptyMap(),
    /** Modalità risparmio: animazioni più lente, orologio fisso e (su Android) luminosità ridotta. */
    val ecoMode: Boolean = false,
    /** Schermo sempre acceso solo con il dispositivo in carica. */
    val screenOnOnlyWhenCharging: Boolean = false,
    /** Fondo e moduli neri, per gli schermi OLED. */
    val oledPalette: Boolean = false,
    /** LED o palette. */
    val boardStyle: BoardStyle = BoardStyle.Led,
    /** Crepitio delle palette quando girano. */
    val flapSound: Boolean = true,
    /** Volume del crepitio delle palette, in percentuale. */
    val flapVolume: Int = 100,
    /** Secondi di pausa su ogni pagina di un testo lungo, dalla fine della girata. */
    val flapPageSeconds: Int = 5,
    /** Secondi di lampeggio della riga del treno annunciato, da quando comincia il jingle. */
    val announceBlinkSeconds: Int = 10,
    /** Volume degli annunci (jingle e voce), in percentuale. */
    val announceVolume: Int = 100,
    /** Sottofondo di stazione in loop. */
    val background: Boolean = true,
    /** Volume del sottofondo, in percentuale. */
    val backgroundVolume: Int = 50,
)

/**
 * Ultime [BoardSettings], salvate con DataStore in un file JSON su [path] (assoluto).
 *
 * Un solo [SettingsStore] per file alla volta: DataStore rifiuta due istanze sullo stesso file.
 */
class SettingsStore(path: String) : AutoCloseable {

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val store = jsonDataStore(path, SettingsSerializer, scope)

    /** Impostazioni ancora da scrivere: se se ne accumulano, si scrivono solo le ultime. */
    private val pending = Channel<BoardSettings>(Channel.CONFLATED)

    private val writer = scope.launch {
        for (settings in pending) {
            try {
                store.updateData { settings }
            } catch (e: IOException) {
                DebugLog.d(TAG) { "Salvataggio delle impostazioni fallito (${e.message})" }
            }
        }
    }

    /** Ultime impostazioni salvate; quelle predefinite se non ce ne sono o il file non si legge. */
    val settings: Flow<BoardSettings> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            DebugLog.d(TAG) { "Lettura delle impostazioni fallita (${e.message})" }
            emit(BoardSettings())
        }

    suspend fun load(): BoardSettings = settings.first()

    /** Salva [settings] senza attendere la scrittura su disco. */
    fun save(settings: BoardSettings) {
        pending.trySend(settings)
    }

    /**
     * Scrive le eventuali impostazioni in sospeso e libera il file: al ritorno se ne può aprire
     * subito un altro [SettingsStore] (es. un ViewModel ricreato).
     */
    override fun close() {
        pending.close()
        blockingClose {
            writer.join()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    companion object {
        const val TAG = "Impostazioni"
    }
}

private object SettingsSerializer : OkioSerializer<BoardSettings> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = BoardSettings()

    override suspend fun readFrom(source: BufferedSource): BoardSettings =
        try {
            json.decodeFromString(BoardSettings.serializer(), source.readUtf8())
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Impostazioni illeggibili", e)
        }

    override suspend fun writeTo(t: BoardSettings, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(BoardSettings.serializer(), t))
    }
}
