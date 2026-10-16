package com.takaotech.departureboard.tts

import com.takaotech.departureboard.resources.Res
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event

/**
 * MP3 in loop con un elemento `<audio>` che punta direttamente alla risorsa: il browser la
 * scarica a pezzi mentre suona, senza passare dalla memoria di Kotlin.
 */
class WebLoopPlayer : LoopPlayer {

    override suspend fun loop(path: String, gain: StateFlow<Float>) {
        val audio = Audio(Res.getUri(path))
        audio.loop = true
        audio.volume = gain.value.toDouble()
        val failed = CompletableDeferred<Unit>()
        audio.onerror =
            { failed.completeExceptionally(IllegalStateException("Sottofondo non riproducibile")) }
        try {
            coroutineScope {
                launch { gain.collect { audio.volume = it.toDouble().coerceIn(0.0, 1.0) } }
                audio.start()
                failed.await()
            }
        } finally {
            audio.pause()
        }
    }

    override fun close() {}
}

/**
 * Avvia [this]. Il browser rifiuta l'audio finché la pagina non ha avuto un gesto dell'utente:
 * allora si riprova dentro il primo click o tasto che arriva (Safari lo vuole proprio lì dentro).
 */
private suspend fun Audio.start() {
    while (true) {
        try {
            play().await<JsAny?>()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val retry = CompletableDeferred<kotlin.js.Promise<JsAny?>>()
            val listener: (Event) -> Unit = { if (!retry.isCompleted) retry.complete(play()) }
            GESTURES.forEach { document.addEventListener(it, listener) }
            try {
                retry.await().await<JsAny?>()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Rifiutata anche nel gesto: si aspetta il prossimo.
            } finally {
                GESTURES.forEach { document.removeEventListener(it, listener) }
            }
        }
    }
}

private val GESTURES = listOf("pointerdown", "keydown")
