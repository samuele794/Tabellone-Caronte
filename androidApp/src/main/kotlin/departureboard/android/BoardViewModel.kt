package departureboard.android

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import announce.Announcer
import cache.StationCache
import debuglog.DebugLog
import departureboard.FlapClatter
import fnm.FnmBoardSource
import fnm.FnmClient
import fnm.FnmKeyStore
import live.LiveBoardState
import live.SettingsStore
import rfi.RfiBoardSource
import rfi.RfiClient
import tts.AndroidPcmPlayer
import tts.AndroidSoundPlayer
import tts.AndroidSpeechEngine
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
