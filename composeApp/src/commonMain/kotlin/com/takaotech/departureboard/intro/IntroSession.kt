package com.takaotech.departureboard.intro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A che punto è l'intro, fuori dalla composizione di [IntroHost]: se questa se ne va a metà
 * (l'Activity ricreata da una rotazione) la prossima riprende da lì invece di saltarla. La musica
 * suona in [scope], che deve vivere quanto la sessione: intanto non si ferma.
 */
class IntroSession(private val scope: CoroutineScope) {
    /** Quando l'intro è comparsa: l'ora del terminale parte da qui. */
    internal val shownAt = Clock.System.now()

    /** Finita, saltata, interrotta o spenta: non riparte più. */
    internal var finished by mutableStateOf(false)
        private set

    /** La grafica è partita: da qui in poi si riprende invece di ricominciare. */
    internal var running by mutableStateOf(false)
    internal var skipRequested by mutableStateOf(false)

    /** Stazione che il terminale prova per prima; scelta una volta sola ([decoyChosen]). */
    internal var decoy by mutableStateOf<Station?>(null)
    internal var decoyChosen = false

    /** Millisecondi di intro mostrati. */
    internal val clock = mutableLongStateOf(0L)

    /** Dissolvenza dell'uscita quando la si salta, 0..1. */
    internal val exit = mutableFloatStateOf(0f)

    /** Da quale millisecondo si sta saltando; -1 se no. */
    internal var skipFrom = -1L

    private var audio: Job? = null
    private var audioStarted: CompletableDeferred<Unit>? = null
    private var detachedAt: TimeMark? = null

    /**
     * Fa suonare [play] in [scope], una volta sola anche se chiamata di nuovo; completa quando la
     * musica comincia a sentirsi, o quando [play] finisce senza che succeda.
     */
    internal fun startAudio(play: suspend (onStart: () -> Unit) -> Unit): Deferred<Unit> =
        audioStarted ?: CompletableDeferred<Unit>().also { started ->
            audioStarted = started
            audio = scope.launch {
                try {
                    play { started.complete(Unit) }
                } finally {
                    started.complete(Unit)
                }
            }
        }

    internal fun stopAudio() {
        audio?.cancel()
    }

    /**
     * La composizione se ne va con l'intro a metà: la musica continua, la grafica si ferma. Prima
     * che la grafica parta non c'è nulla da recuperare: la prossima riparte dall'inizio.
     */
    internal fun detach() {
        if (running) detachedAt = TimeSource.Monotonic.markNow()
    }

    /**
     * Da dove riprende la grafica: quanto aveva già mostrato più il tempo passato senza
     * composizione, così resta al passo con la musica che intanto è andata avanti.
     */
    internal fun resumeMillis(): Long {
        val gap = detachedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
        detachedAt = null
        return clock.longValue + gap
    }

    internal fun finish() {
        stopAudio()
        finished = true
    }
}

/** Sessione legata alla composizione: dove la composizione non viene mai ricreata basta questa. */
@Composable
fun rememberIntroSession(): IntroSession {
    val scope = rememberCoroutineScope()
    return remember(scope) { IntroSession(scope) }
}
