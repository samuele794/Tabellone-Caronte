package departureboard.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import announce.Announcer
import cache.StationCache
import fnm.FnmBoardSource
import fnm.FnmClient
import fnm.FnmKeyStore
import live.LiveBoardState
import rfi.RfiBoardSource
import rfi.RfiClient
import tts.AndroidSoundPlayer
import tts.AndroidSpeechEngine
import java.io.File

/**
 * Tiene tabellone, client e annunci fuori dall'Activity, così una rotazione non riparte da zero
 * (stazione, modalità, impostazioni e ultimi dati restano; il TTS non viene ricreato).
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val stationCache =
        StationCache(File(app.filesDir, "datastore/stations.json").absolutePath)
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

    val state = LiveBoardState(sources, announcer = announcer, stationCache = stationCache)

    override fun onCleared() {
        announcer.close()
        stationCache.close()
        sources.values.forEach { it.close() }
    }
}
