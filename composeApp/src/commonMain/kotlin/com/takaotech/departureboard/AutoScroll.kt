package com.takaotech.departureboard

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.State
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
import kotlin.math.floor
import kotlin.math.roundToInt

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
 * estremi. Si muove a scatti di un punto LED ([dotPx]) allo scatto del [ticker], come i moduli
 * veri: un frame per scatto invece che uno a ogni vsync, e fermo quando il [ticker] è fermo.
 * Se l'utente scorre si ferma, e [resumeMillis] dopo il suo ultimo spostamento riparte nella
 * stessa direzione.
 */
internal suspend fun ScrollState.autoScroll(
    bottom: Int,
    pxPerSecond: Float,
    dotPx: Float,
    ticker: State<Long>,
    pauseMillis: Long,
    resumeMillis: Long,
    user: UserScrollTracker,
) {
    var down = true
    var pause = pauseMillis
    while (true) {
        val interrupted = user.interrupts {
            delay(pause)
            scrollInDotsTo(if (down) bottom else 0, pxPerSecond, dotPx, ticker)
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
private suspend fun UserScrollTracker.interrupts(block: suspend () -> Unit): Boolean =
    coroutineScope {
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

/** Scorre a velocità costante fino a [target], spostandosi solo allo scatto del [ticker]. */
private suspend fun ScrollState.scrollInDotsTo(
    target: Int,
    pxPerSecond: Float,
    dotPx: Float,
    ticker: State<Long>,
) {
    val start = value
    val startTick = ticker.value
    var tick = startTick
    // Si guarda la posizione calcolata, non [value]: se il contenuto si accorcia, scrollTo si
    // ferma prima di [target] e il ciclo non finirebbe mai.
    do {
        tick = snapshotFlow { ticker.value }.first { it != tick }
        val position = dotPosition(start, target, dotPx, pxPerSecond * (tick - startTick) / 1000f)
        if (position != value) scrollTo(position)
    } while (position != target)
}

/**
 * Posizione dopo aver percorso [travelledPx] da [start] verso [target], a punti LED interi
 * ([dotPx]): l'ultimo passo si ferma esattamente su [target].
 */
internal fun dotPosition(start: Int, target: Int, dotPx: Float, travelledPx: Float): Int {
    val dots = floor(travelledPx / dotPx.coerceAtLeast(1f))
    val px = (dots * dotPx.coerceAtLeast(1f)).roundToInt()
    if (px >= abs(target - start)) return target
    return if (target > start) start + px else start - px
}
