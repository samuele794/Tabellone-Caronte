package departureboard.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import announce.Announcer
import live.LiveBoardState
import rfi.RfiClient
import tts.AndroidSoundPlayer
import tts.AndroidSpeechEngine

/**
 * Tiene tabellone, client e annunci fuori dall'Activity, così una rotazione non riparte da zero
 * (stazione, modalità, impostazioni e ultimi dati restano; il TTS non viene ricreato).
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val client = RfiClient()
    private val announcer = Announcer(AndroidSpeechEngine(app), AndroidSoundPlayer(app))

    val state = LiveBoardState(client, announcer = announcer)

    override fun onCleared() {
        announcer.close()
        client.close()
    }
}
