package com.takaotech.departureboard.cache

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

internal actual fun <T> jsonDataStore(
    path: String,
    serializer: OkioSerializer<T>,
    scope: CoroutineScope
): DataStore<T> =
    DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, serializer) { path.toPath() },
        corruptionHandler = ReplaceFileCorruptionHandler { serializer.defaultValue },
        scope = scope,
    )

internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

internal actual fun blockingClose(block: suspend () -> Unit) = runBlocking { block() }
