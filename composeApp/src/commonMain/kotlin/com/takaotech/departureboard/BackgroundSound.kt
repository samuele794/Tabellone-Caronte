package com.takaotech.departureboard

import com.takaotech.departureboard.BackgroundSound.Companion.DUCKED_VOLUME
import com.takaotech.departureboard.BackgroundSound.Companion.FADE
import com.takaotech.departureboard.BackgroundSound.Companion.QUIET_END
import com.takaotech.departureboard.BackgroundSound.Companion.QUIET_START
import com.takaotech.departureboard.BackgroundSound.Companion.RECHECK
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.tts.LoopPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sottofondo di stazione: [track] suonata in loop da [player]. Di serie l'ambiente di Milano
 * Centrale in `composeResources/files`.
 *
 * Di notte, tra [QUIET_START] e [QUIET_END], tace qualunque cosa dicano le impostazioni
 * ([playOutsideQuietHours]).
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

    private val duckRamp = Ramp(0f)
    private val fadeRamp = Ramp(1f)

    /**
     * Quanto è abbassato il sottofondo: 0 = al [volume], 1 = a [DUCKED_VOLUME] (o al [volume], se
     * è già più basso). Lo cambiano [duck] e [restore], a poco a poco.
     */
    val ducking: Float get() = duckRamp.value

    /** Sfumatura di avvio e spegnimento notturno: 0 = muto, 1 = al volume (eventualmente abbassato). */
    val fade: Float get() = fadeRamp.value

    /** Abbassa il sottofondo fino a [DUCKED_VOLUME] in [over], dal punto in cui è adesso. */
    fun duck(over: Duration) = duckRamp.to(1f, over)

    /** Riporta il sottofondo al [volume] in [over], dal punto in cui è adesso. */
    fun restore(over: Duration = RESTORE_DURATION) = duckRamp.to(0f, over)

    private val _gain = MutableStateFlow(1f)

    /** Guadagno che il player applica adesso, da [volume], [ducking] e [fade]. */
    val gain: StateFlow<Float> = _gain.asStateFlow()

    private fun updateGain() {
        val low = minOf(volume, DUCKED_VOLUME)
        val position = (volume + (low - volume) * ducking) * fade
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

    /**
     * Suona il sottofondo, ma non tra [QUIET_START] e [QUIET_END] dell'ora locale [now]: allo
     * scoccare di [QUIET_START] sfuma in [FADE] e si ferma, a [QUIET_END] riparte sfumando da muto.
     * Avviato fuori da quelle ore parte subito al volume pieno. Gira finché non viene cancellata.
     */
    suspend fun playOutsideQuietHours(now: () -> LocalTime = ::localTimeNow) {
        var fadeIn = false
        while (true) {
            if (isQuiet(now())) {
                fadeRamp.set(0f)
                DebugLog.d(TAG) { "Ore di silenzio: riparte alle $QUIET_END" }
                waitUntil(QUIET_END, now)
                fadeIn = true
                continue
            }
            coroutineScope {
                if (fadeIn) {
                    fadeRamp.set(0f)
                    fadeRamp.to(1f, FADE)
                } else {
                    fadeRamp.set(1f)
                }
                val playing = launch { play() }
                waitUntil(QUIET_START, now)
                DebugLog.d(TAG) { "Ore di silenzio: sfumo e mi fermo" }
                fadeRamp.to(0f, FADE)
                fadeRamp.join()
                playing.cancelAndJoin()
            }
        }
    }

    override fun close() {
        scope.cancel()
        player.close()
    }

    /**
     * Valore da 0 a 1 che va in linea retta verso un obiettivo nel tempo; una rampa nuova
     * sostituisce quella in corso e parte da dove era arrivata.
     */
    private inner class Ramp(initial: Float) {
        @Volatile
        var value: Float = initial
            private set(v) {
                field = v
                updateGain()
            }

        @Volatile
        private var job: Job? = null

        /** Salta subito a [target], fermando la rampa in corso. */
        fun set(target: Float) {
            job?.cancel()
            value = target
        }

        fun to(target: Float, over: Duration) {
            job?.cancel()
            val from = value
            if (from == target) return
            job = scope.launch {
                val start = timeSource.markNow()
                while (true) {
                    val elapsed = start.elapsedNow()
                    if (elapsed >= over) break
                    value = from + (target - from) * (elapsed / over).toFloat()
                    delay(minOf(RAMP_STEP, over - elapsed))
                }
                value = target
            }
        }

        /** Attende la fine della rampa in corso, se c'è. */
        suspend fun join() {
            job?.join()
        }
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

        /** Inizio delle ore di silenzio, in cui il sottofondo non suona mai. */
        val QUIET_START = LocalTime(1, 0)

        /** Fine delle ore di silenzio: il sottofondo riparte. */
        val QUIET_END = LocalTime(5, 0)

        /** Sfumatura di spegnimento a [QUIET_START] e di avvio a [QUIET_END]. */
        val FADE = 10.seconds

        /** Ogni quanto la rampa aggiorna il guadagno: abbastanza fitto da non sentire scalini. */
        private val RAMP_STEP = 40.milliseconds

        /**
         * Attesa più lunga prima di ricontrollare l'ora: così cambi dell'ora legale, dell'orologio
         * di sistema o una sospensione del computer spostano l'attesa al più di tanto.
         */
        private val RECHECK = 1.minutes

        /** Se [time] cade nelle ore di silenzio: da [QUIET_START] incluso a [QUIET_END] escluso. */
        fun isQuiet(time: LocalTime): Boolean = time >= QUIET_START && time < QUIET_END

        /** Tempo da [from] alla prossima volta che l'orologio segna [to] (zero se coincidono). */
        internal fun timeUntil(from: LocalTime, to: LocalTime): Duration {
            val diff = (to.toNanosecondOfDay() - from.toNanosecondOfDay()).nanoseconds
            return if (diff < Duration.ZERO) diff + 1.days else diff
        }

        /** Attende che l'ora locale [now] arrivi a [target], ricontrollandola almeno ogni [RECHECK]. */
        private suspend fun waitUntil(target: LocalTime, now: () -> LocalTime) {
            while (true) {
                val left = timeUntil(now(), target)
                if (left == Duration.ZERO) return
                if (left <= RECHECK) {
                    delay(left)
                    return
                }
                delay(RECHECK)
            }
        }

        private fun localTimeNow(): LocalTime =
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
}
