package com.takaotech.departureboard.wear

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.watch.WatchStore
import com.takaotech.departureboard.wear.data.WatchRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class WearApp : Application() {
    /**
     * Uno solo per processo, condiviso da app, tile, complication e servizio del Data Layer:
     * DataStore rifiuta due istanze sullo stesso file.
     */
    val refresher by lazy {
        WatchRefresher(this, WatchStore(File(filesDir, "datastore/watch.json").absolutePath))
    }

    /** Lavori che devono finire anche se chi li ha chiesti (un servizio) se ne va. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Il log di debug serve solo nelle build di sviluppo.
        DebugLog.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

val Context.wearApp: WearApp get() = applicationContext as WearApp
