package departureboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Registra lo scroll fatto dall'utente (rotella, trackpad, trascinamento). Va messo sopra il
 * contenitore scorrevole con `Modifier.nestedScroll`: gli scroll da codice non passano di qui.
 */
internal class UserScrollTracker : NestedScrollConnection {
    /** Cresce a ogni spostamento dell'utente. */
    var moves by mutableIntStateOf(0)
        private set

    /** Direzione dell'ultimo spostamento: true = verso le ultime righe. */
    var down = true
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f) {
            down = available.y < 0
            moves++
        }
        return Offset.Zero
    }
}

/**
 * Scorre tra 0 e [bottom] avanti e indietro a velocità costante, con [pauseMillis] di sosta agli
 * estremi. Se l'utente scorre si ferma, e [resumeMillis] dopo il suo ultimo spostamento riparte
 * nella stessa direzione.
 */
internal suspend fun ScrollState.autoScroll(
    bottom: Int,
    pxPerSecond: Float,
    pauseMillis: Long,
    resumeMillis: Long,
    user: UserScrollTracker,
) {
    var down = true
    var pause = pauseMillis
    while (true) {
        val interrupted = user.interrupts {
            delay(pause)
            scrollLinearlyTo(if (down) bottom else 0, pxPerSecond)
        }
        if (interrupted) {
            user.awaitIdle(resumeMillis)
            down = user.down
            pause = 0
        } else {
            down = !down
            pause = pauseMillis
        }
    }
}

/** Esegue [block] annullandolo se l'utente scorre nel frattempo; true se è stato interrotto. */
private suspend fun UserScrollTracker.interrupts(block: suspend () -> Unit): Boolean = coroutineScope {
    val start = moves
    val job = launch { block() }
    val watcher = launch {
        snapshotFlow { moves }.first { it != start }
        job.cancel()
    }
    job.join()
    watcher.cancel()
    // Lo scroll dell'utente annulla anche da sé l'animazione in corso (priorità UserInput).
    job.isCancelled
}

/** Ritorna quando l'utente non scorre da [millis]. */
private suspend fun UserScrollTracker.awaitIdle(millis: Long) {
    while (true) {
        val seen = moves
        withTimeoutOrNull(millis) { snapshotFlow { moves }.first { it != seen } } ?: return
    }
}

/** Scorre a velocità costante, qualunque sia la distanza da percorrere. */
private suspend fun ScrollState.scrollLinearlyTo(target: Int, pxPerSecond: Float) {
    val millis = (abs(target - value) / pxPerSecond * 1000).toInt()
    animateScrollTo(target, tween(millis, easing = LinearEasing))
}
