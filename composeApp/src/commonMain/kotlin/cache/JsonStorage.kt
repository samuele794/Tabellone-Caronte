package cache

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * DataStore per il JSON di [path]: un file su desktop e Android, una voce di `localStorage` nel
 * browser (dove [path] è solo il nome della voce). Se il contenuto non si legge
 * ([androidx.datastore.core.CorruptionException]) riparte da `serializer.defaultValue`.
 */
internal expect fun <T> jsonDataStore(
    path: String,
    serializer: OkioSerializer<T>,
    scope: CoroutineScope
): DataStore<T>

/** Dispatcher per l'I/O di DataStore: `Dispatchers.IO` dove c'è, quello predefinito nel browser. */
internal expect val ioDispatcher: CoroutineDispatcher

/**
 * Esegue [block] e ritorna quando è finito; nel browser, dove non si può bloccare, lo avvia
 * soltanto.
 */
internal expect fun blockingClose(block: suspend () -> Unit)
