package com.takaotech.departureboard

import com.takaotech.departureboard.BackgroundSound.Companion.DUCKED_VOLUME
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.tts.LoopPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sottofondo di stazione: [track] suonata in loop da [player] finché [play] non viene cancellata.
 * Di serie l'ambiente di Milano Centrale in `composeResources/files`.
 */
class BackgroundSound(
    private val player: LoopPlayer,
    private val track: String = TRACK,
    context: CoroutineContext = Dispatchers.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + context)

    /**
     * Volume del sottofondo, da 0 (muto) a 1 (pieno), come la posizione di un cursore: il
     * guadagno è il quadrato, come per [FlapClatter.volume].
     */
    @Volatile
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateGain()
        }

    /**
     * Quanto è abbassato il sottofondo: 0 = al [volume], 1 = a [DUCKED_VOLUME] (o al [volume], se
     * è già più basso). Lo cambiano [duck] e [restore], a poco a poco.
     */
    @Volatile
    var ducking: Float = 0f
        private set(value) {
            field = value
            updateGain()
        }

    @Volatile
    private var ramp: Job? = null

    /** Abbassa il sottofondo fino a [DUCKED_VOLUME] in [over], dal punto in cui è adesso. */
    fun duck(over: Duration) = rampTo(1f, over)

    /** Riporta il sottofondo al [volume] in [over], dal punto in cui è adesso. */
    fun restore(over: Duration = RESTORE_DURATION) = rampTo(0f, over)

    /**
     * Porta [ducking] a [target] in linea retta nel tempo; una rampa nuova sostituisce quella in
     * corso e parte da dove era arrivata.
     */
    private fun rampTo(target: Float, over: Duration) {
        ramp?.cancel()
        val from = ducking
        if (from == target) return
        ramp = scope.launch {
            val start = timeSource.markNow()
            while (true) {
                val elapsed = start.elapsedNow()
                if (elapsed >= over) break
                ducking = from + (target - from) * (elapsed / over).toFloat()
                delay(minOf(RAMP_STEP, over - elapsed))
            }
            ducking = target
        }
    }

    private val _gain = MutableStateFlow(1f)

    /** Guadagno che il player applica adesso, da [volume] e [ducking]. */
    val gain: StateFlow<Float> = _gain.asStateFlow()

    private fun updateGain() {
        val low = minOf(volume, DUCKED_VOLUME)
        val position = volume + (low - volume) * ducking
        _gain.value = position * position
    }

    /** Suona il sottofondo finché non viene cancellata; se il player si ferma per un errore, ritorna. */
    suspend fun play() {
        try {
            DebugLog.d(TAG) { "Sottofondo avviato" }
            player.loop(track, gain)
        } catch (e: CancellationException) {
            DebugLog.d(TAG) { "Sottofondo fermato" }
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "Sottofondo non riprodotto: $e" }
        }
    }

    override fun close() {
        scope.cancel()
        player.close()
    }

    companion object {
        const val TAG = "Sottofondo"

        /** Ambiente di Milano Centrale: 20 minuti, MP3 mono a 96 kbps. */
        const val TRACK = "files/milano-central-background.mp3"

        /** Posizione del cursore del volume durante un annuncio (se non è già più bassa). */
        const val DUCKED_VOLUME = 0.05f

        /** Quanto ci mette il sottofondo a tornare al volume scelto, a fine annuncio. */
        val RESTORE_DURATION = 3.seconds

        /** Abbassamento senza jingle (o di durata sconosciuta): rapido, prima che parli la voce. */
        val QUICK_DUCK = 500.milliseconds

        /** Ogni quanto la rampa aggiorna il guadagno: abbastanza fitto da non sentire scalini. */
        private val RAMP_STEP = 40.milliseconds
    }
}
