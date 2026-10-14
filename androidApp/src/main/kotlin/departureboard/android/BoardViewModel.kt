package departureboard.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import announce.Announcer
import cache.StationCache
import live.LiveBoardState
import rfi.RfiClient
import tts.AndroidSoundPlayer
import tts.AndroidSpeechEngine
import java.io.File

/**
 * Tiene tabellone, client e annunci fuori dall'Activity, così una rotazione non riparte da zero
 * (stazione, modalità, impostazioni e ultimi dati restano; il TTS non viene ricreato).
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val client = RfiClient()
    private val stationCache =
        StationCache(File(app.filesDir, "datastore/stations.json").absolutePath)
    private val announcer = Announcer(AndroidSpeechEngine(app), AndroidSoundPlayer(app))

    val state = LiveBoardState(client, announcer = announcer, stationCache = stationCache)

    override fun onCleared() {
        announcer.close()
        stationCache.close()
        client.close()
    }
}
