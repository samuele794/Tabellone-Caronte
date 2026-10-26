package com.takaotech.departureboard.wear.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.concurrent.futures.await
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.wear.data.WatchRefresher
import com.takaotech.departureboard.wear.wearApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class TrainActivity : ComponentActivity() {

    private var ambient by mutableStateOf(false)

    /** Ora dell'orologio del monitor: avanza a ogni minuto, anche in ambient. */
    private var now by mutableStateOf(System.currentTimeMillis())

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient = true
            }

            override fun onExitAmbient() {
                ambient = false
                now = System.currentTimeMillis()
            }

            override fun onUpdateAmbient() {
                now = System.currentTimeMillis()
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        val refresher = wearApp.refresher
        setContent {
            val snapshot by refresher.store.snapshot.collectAsState(WatchSnapshot())
            val refreshing by refresher.refreshingNow.collectAsState()
            val scope = rememberCoroutineScope()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(Unit) { refresher.syncFromPhone() }
            // Mentre si guarda (e non in ambient) si aggiorna ogni REFRESH_INTERVAL.
            LaunchedEffect(ambient, lifecycle) {
                if (ambient) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        refresher.refresh()
                        delay(REFRESH_INTERVAL)
                    }
                }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
                }
            }
            MaterialTheme {
                // L'ora la dà l'orologio del monitor, non serve quella di sistema in alto.
                AppScaffold(timeText = {}) {
                    TrainScreen(
                        snapshot = snapshot,
                        nowMillis = now,
                        ambient = ambient,
                        refreshing = refreshing,
                        onRefresh = { scope.launch { refresher.refresh() } },
                        onOpenPhone = { scope.launch { openOnPhone() } },
                    )
                }
            }
        }
    }

    /** Apre l'app sul telefono abbinato, dove si sceglie il treno. */
    private suspend fun openOnPhone() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PHONE_URI))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            RemoteActivityHelper(this).startRemoteActivity(intent).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(WatchRefresher.TAG) { "App telefono non aperta (${e.message})" }
        }
    }

    private companion object {
        val REFRESH_INTERVAL = 30.seconds

        const val MINUTE_MILLIS = 60_000L

        /** Intent filter di MainActivity nell'app telefono. */
        const val PHONE_URI = "tabellone://smartwatch"
    }
}
