package com.takaotech.departureboard.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await

/** MP3 con un elemento `<audio>`; i byte passano da un Blob URL. */
class WebSoundPlayer : SoundPlayer {

    override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) {
        val url = mp3ObjectUrl(bytes.toUint8Array())
        val audio = Audio(url)
        audio.volume = volume.toDouble().coerceIn(0.0, 1.0)
        try {
            val ended = CompletableDeferred<Unit>()
            audio.onended = { ended.complete(Unit) }
            audio.onerror =
                { ended.completeExceptionally(IllegalStateException("Audio non riproducibile")) }
            // Rifiutata se il browser blocca la riproduzione perché manca un gesto dell'utente.
            audio.play().await<JsAny?>()
            // La promessa si risolve quando la riproduzione è cominciata.
            onStart()
            ended.await()
        } finally {
            audio.pause()
            revokeObjectUrl(url)
        }
    }

    override fun close() {}
}

internal external class Audio(src: String) : JsAny {
    var onended: ((JsAny) -> Unit)?
    var onerror: ((JsAny) -> Unit)?
    var volume: Double
    var loop: Boolean
    fun play(): kotlin.js.Promise<JsAny?>
    fun pause()
}

private fun ByteArray.toUint8Array(): JsAny {
    val array = newUint8Array(size)
    forEachIndexed { i, b -> setByte(array, i, b.toInt() and 0xFF) }
    return array
}

private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")

private fun setByte(array: JsAny, index: Int, value: Int): Unit = js("array[index] = value")

private fun mp3ObjectUrl(data: JsAny): String =
    js("URL.createObjectURL(new Blob([data], { type: 'audio/mpeg' }))")

private fun revokeObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")
