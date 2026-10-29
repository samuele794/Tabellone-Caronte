package com.takaotech.departureboard.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.takaotech.departureboard.android.watch.PhoneWatchSync
import com.takaotech.departureboard.cache.StationCache
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.fnm.FnmBoardSource
import com.takaotech.departureboard.fnm.FnmClient
import com.takaotech.departureboard.fnm.FnmKeyStore
import com.takaotech.departureboard.live.SettingsStore
import com.takaotech.departureboard.rfi.RfiBoardSource
import com.takaotech.departureboard.rfi.RfiClient
import com.takaotech.departureboard.viaggiatreno.ViaggiaTrenoClient
import com.takaotech.departureboard.watch.TrainTracker
import java.io.File

class DepartureBoardApp : Application() {
    /** Creati al primo uso e tenuti per tutta la vita del processo. */
    val services by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()
        // Il log di debug serve solo nelle build di sviluppo.
        DebugLog.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

val Context.services: AppServices get() = (applicationContext as DepartureBoardApp).services

/**
 * Quello che tabellone e smartwatch condividono nel processo. DataStore rifiuta due istanze sullo
 * stesso file, e il servizio che risponde al watch può girare insieme all'Activity: cache,
 * impostazioni e chiave FNM esistono quindi una volta sola e non si chiudono mai.
 */
class AppServices(app: Application) {
    val stationCache = StationCache(File(app.filesDir, "datastore/stations.json").absolutePath)
    val settingsStore = SettingsStore(File(app.filesDir, "datastore/settings.json").absolutePath)
    private val fnmClient = FnmClient()
    private val fnmKeys = FnmKeyStore(
        File(app.filesDir, "datastore/fnm-key.json").absolutePath,
        fnmClient::webComponentsJs,
    )

    /** Fornitori del tabellone. */
    val sources = listOf(
        RfiBoardSource(RfiClient()),
        FnmBoardSource(fnmClient, fnmKeys),
    ).associateBy { it.source }

    /** Posizioni delle stazioni, per la mappa. */
    val viaggiaTreno = ViaggiaTrenoClient()

    /** Fornitori a parte per lo smartwatch: vedi [TrainTracker]. */
    private val trainTracker = TrainTracker(
        listOf(
            RfiBoardSource(RfiClient()),
            FnmBoardSource(fnmClient, fnmKeys),
        ).associateBy { it.source }
    )

    val watchSync = PhoneWatchSync(app, trainTracker, settingsStore)
}
