package com.takaotech.departureboard.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.live.LiveBoardState
import com.takaotech.departureboard.tts.AndroidLoopPlayer
import com.takaotech.departureboard.tts.AndroidPcmPlayer
import com.takaotech.departureboard.tts.AndroidSoundPlayer
import com.takaotech.departureboard.tts.AndroidSpeechEngine

/**
 * Tiene tabellone, client e annunci fuori dall'Activity, così una rotazione non riparte da zero
 * (stazione, modalità, impostazioni e ultimi dati restano; il TTS non viene ricreato).
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val services = app.services
    private val announcer = Announcer(AndroidSpeechEngine(app), AndroidSoundPlayer(app))
    private val flapClatter = FlapClatter(AndroidPcmPlayer())
    private val backgroundSound = BackgroundSound(AndroidLoopPlayer(app))

    /** Musica dell'intro d'avvio. */
    val introPlayer = AndroidSoundPlayer(app)

    /**
     * L'intro è già stata vista (o saltata, o interrotta) in questo processo: una rotazione non la
     * fa ripartire.
     */
    var introDone = false

    val state = LiveBoardState(
        services.sources,
        announcer = announcer,
        stationCache = services.stationCache,
        settingsStore = services.settingsStore,
        flapClatter = flapClatter,
        backgroundSound = backgroundSound,
        watchSync = services.watchSync,
        viaggiaTreno = services.viaggiaTreno,
    )

    /** Cache, impostazioni e fornitori restano aperti: sono del processo ([AppServices]). */
    override fun onCleared() {
        announcer.close()
        flapClatter.close()
        backgroundSound.close()
    }
}
