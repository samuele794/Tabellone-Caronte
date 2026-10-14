package cache

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.IOException

// DataStore 1.2.1 pubblica la variante wasmJs, ma lì DataStoreFactory.create è ancora un TODO():
// nel browser si usa un DataStore minimo su localStorage, con le stesse interfacce e serializer.
internal actual fun <T> jsonDataStore(
    path: String,
    serializer: OkioSerializer<T>,
    scope: CoroutineScope
): DataStore<T> =
    LocalStorageDataStore(path, serializer)

internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

@OptIn(DelicateCoroutinesApi::class)
internal actual fun blockingClose(block: suspend () -> Unit) {
    GlobalScope.launch { block() }
}

/**
 * Il JSON della voce [key] di `localStorage`, che legge e scrive in modo sincrono. Una pagina è
 * un solo processo e un solo thread: basta un [Mutex] per mettere in fila gli aggiornamenti.
 */
private class LocalStorageDataStore<T>(
    private val key: String,
    private val serializer: OkioSerializer<T>,
) : DataStore<T> {

    /** Box per distinguere "non ancora letto" (null) da un valore null di T. */
    private class Value<T>(val value: T)

    private val mutex = Mutex()
    private val current = MutableStateFlow<Value<T>?>(null)

    override val data: Flow<T> = flow {
        load()
        emitAll(current.filterNotNull().map { it.value })
    }

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        val old = loaded()
        val new = transform(old)
        if (new != old) {
            write(new)
            current.value = Value(new)
        }
        new
    }

    private suspend fun load() {
        if (current.value == null) mutex.withLock { loaded() }
    }

    /** Valore attuale, letto da `localStorage` la prima volta; da chiamare con [mutex] preso. */
    private suspend fun loaded(): T {
        current.value?.let { return it.value }
        val value = read()
        current.value = Value(value)
        return value
    }

    private suspend fun read(): T {
        val text = localStorage.getItem(key) ?: return serializer.defaultValue
        return try {
            serializer.readFrom(Buffer().writeUtf8(text))
        } catch (e: CorruptionException) {
            serializer.defaultValue
        }
    }

    private suspend fun write(value: T) {
        val buffer = Buffer()
        serializer.writeTo(value, buffer)
        try {
            localStorage.setItem(key, buffer.readUtf8())
        } catch (e: Throwable) {
            // Spazio esaurito o storage disattivato: per gli store è un errore di I/O.
            throw IOException("localStorage: ${e.message}")
        }
    }
}
