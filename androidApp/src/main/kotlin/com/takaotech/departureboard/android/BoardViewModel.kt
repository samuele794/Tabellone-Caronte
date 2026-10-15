package com.takaotech.departureboard.android

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.cache.StationCache
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.fnm.FnmBoardSource
import com.takaotech.departureboard.fnm.FnmClient
import com.takaotech.departureboard.fnm.FnmKeyStore
import com.takaotech.departureboard.live.LiveBoardState
import com.takaotech.departureboard.live.SettingsStore
import com.takaotech.departureboard.rfi.RfiBoardSource
import com.takaotech.departureboard.rfi.RfiClient
import com.takaotech.departureboard.tts.AndroidPcmPlayer
import com.takaotech.departureboard.tts.AndroidSoundPlayer
import com.takaotech.departureboard.tts.AndroidSpeechEngine
import java.io.File

/**
 * Tiene tabellone, client e annunci fuori dall'Activity, così una rotazione non riparte da zero
 * (stazione, modalità, impostazioni e ultimi dati restano; il TTS non viene ricreato).
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {
    init {
        // Il log di debug serve solo nelle build di sviluppo.
        DebugLog.enabled = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private val stationCache =
        StationCache(File(app.filesDir, "datastore/stations.json").absolutePath)
    private val settingsStore =
        SettingsStore(File(app.filesDir, "datastore/settings.json").absolutePath)
    private val fnmClient = FnmClient()
    private val sources = listOf(
        RfiBoardSource(RfiClient()),
        FnmBoardSource(
            fnmClient,
            FnmKeyStore(
                File(app.filesDir, "datastore/fnm-key.json").absolutePath,
                fnmClient::webComponentsJs
            ),
        ),
    ).associateBy { it.source }
    private val announcer = Announcer(AndroidSpeechEngine(app), AndroidSoundPlayer(app))
    private val flapClatter = FlapClatter(AndroidPcmPlayer())

    val state = LiveBoardState(
        sources,
        announcer = announcer,
        stationCache = stationCache,
        settingsStore = settingsStore,
        flapClatter = flapClatter,
    )

    override fun onCleared() {
        announcer.close()
        flapClatter.close()
        stationCache.close()
        settingsStore.close()
        sources.values.forEach { it.close() }
    }
}
