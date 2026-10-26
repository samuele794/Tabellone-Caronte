package com.takaotech.departureboard.watch

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.takaotech.departureboard.cache.blockingClose
import com.takaotech.departureboard.cache.ioDispatcher
import com.takaotech.departureboard.cache.jsonDataStore
import com.takaotech.departureboard.debuglog.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/**
 * L'ultimo [WatchSnapshot] dello smartwatch, salvato con DataStore in un file JSON su [path]
 * (assoluto): app, tile e complication lo ritrovano anche senza rete e dopo un riavvio.
 *
 * Un solo [WatchStore] per file alla volta: DataStore rifiuta due istanze sullo stesso file.
 */
class WatchStore(path: String) : AutoCloseable {

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val store = jsonDataStore(path, WatchSnapshotSerializer, scope)

    /** Ultimo snapshot salvato; uno vuoto se non ce n'è o il file non si legge. */
    val snapshot: Flow<WatchSnapshot> = store.data
        .catch { e ->
            if (e !is IOException) throw e
            DebugLog.d(TAG) { "Lettura fallita (${e.message})" }
            emit(WatchSnapshot())
        }

    suspend fun load(): WatchSnapshot = snapshot.first()

    /** Cambia lo snapshot in modo atomico; se non si riesce a scrivere, resta quello di prima. */
    suspend fun update(transform: (WatchSnapshot) -> WatchSnapshot) {
        try {
            store.updateData(transform)
        } catch (e: IOException) {
            DebugLog.d(TAG) { "Salvataggio fallito (${e.message})" }
        }
    }

    override fun close() = blockingClose { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val TAG = "Smartwatch"
    }
}

private object WatchSnapshotSerializer : OkioSerializer<WatchSnapshot> {
    override val defaultValue = WatchSnapshot()

    override suspend fun readFrom(source: BufferedSource): WatchSnapshot =
        WatchSnapshot.decode(source.readUtf8()) ?: throw CorruptionException("Snapshot illeggibile")

    override suspend fun writeTo(t: WatchSnapshot, sink: BufferedSink) {
        sink.writeUtf8(t.encode())
    }
}
