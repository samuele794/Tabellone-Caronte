package com.takaotech.departureboard.tts

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sintesi vocale del browser (Web Speech API) con la prima voce italiana disponibile. I browser
 * la attivano solo dopo un gesto dell'utente: vedi [unlockSpeech].
 */
class WebSpeechEngine : SpeechEngine {

    override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) =
        suspendCancellableCoroutine { cont ->
            val utterance = SpeechSynthesisUtterance(text)
            utterance.lang = "it-IT"
            utterance.volume = volume.coerceIn(0f, 1f)
            italianVoice()?.let { utterance.voice = it }
            utterance.onstart = { if (cont.isActive) onStart() }
            utterance.onend = { if (cont.isActive) cont.resume(Unit) }
            utterance.onerror = { event ->
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Sintesi vocale: ${event.error}"))
            }
            cont.invokeOnCancellation { speechSynthesis.cancel() }
            speechSynthesis.speak(utterance)
        }

    private fun italianVoice(): SpeechSynthesisVoice? {
        val voices = speechSynthesis.getVoices()
        return (0 until voices.length).mapNotNull { voices[it] }
            .firstOrNull { it.lang.startsWith("it") }
    }

    override fun close() = speechSynthesis.cancel()
}

/** Da chiamare dentro un gesto dell'utente: Safari e Chrome sbloccano così la sintesi vocale. */
fun unlockSpeech() = speechSynthesis.speak(SpeechSynthesisUtterance(""))

private external class SpeechSynthesisUtterance(text: String) : JsAny {
    var lang: String
    var voice: SpeechSynthesisVoice?
    var volume: Float
    var onstart: ((JsAny) -> Unit)?
    var onend: ((JsAny) -> Unit)?
    var onerror: ((SpeechSynthesisErrorEvent) -> Unit)?
}

private external interface SpeechSynthesisErrorEvent : JsAny {
    val error: String
}

private external interface SpeechSynthesisVoice : JsAny {
    val lang: String
}

private external interface SpeechSynthesis : JsAny {
    fun speak(utterance: SpeechSynthesisUtterance)
    fun cancel()
    fun getVoices(): JsArray<SpeechSynthesisVoice>
}

private external val speechSynthesis: SpeechSynthesis
