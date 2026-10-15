package com.takaotech.departureboard.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PCM con la Web Audio API. L'[AudioContext] è uno solo per la pagina e parte sospeso: il browser
 * lo attiva solo dentro un gesto dell'utente, vedi [unlockAudio]. Finché è sospeso i suoni si
 * scartano invece di accumularsi.
 */
class WebPcmPlayer : PcmPlayer {

    override suspend fun play(samples: ShortArray, sampleRate: Int, gain: StateFlow<Float>) {
        if (samples.isEmpty() || audioContext.state != "running") return
        val buffer = audioContext.createBuffer(1, samples.size, sampleRate.toFloat())
        val data = buffer.getChannelData(0)
        samples.forEachIndexed { i, s -> setFloat(data, i, s / 32_768f) }
        val source = audioContext.createBufferSource()
        source.buffer = buffer
        val volume = audioContext.createGain()
        volume.gain.value = gain.value
        source.connect(volume)
        volume.connect(audioContext.destination)
        val ended = CompletableDeferred<Unit>()
        source.onended = { ended.complete(Unit) }
        source.start()
        try {
            coroutineScope {
                // Il guadagno segue i cambi anche a riproduzione iniziata, con una rampa breve.
                val follow = launch {
                    gain.collect {
                        volume.gain.setTargetAtTime(
                            it,
                            audioContext.currentTime,
                            GAIN_RAMP_SECONDS
                        )
                    }
                }
                ended.await()
                follow.cancel()
            }
        } finally {
            if (!ended.isCompleted) source.stop()
        }
    }

    override fun close() {}
}

/** Da chiamare dentro un gesto dell'utente: attiva l'audio generato dalla pagina. */
fun unlockAudio() {
    audioContext.resume()
}

private val audioContext by lazy { AudioContext() }

/** Costante di tempo della rampa di guadagno. */
private const val GAIN_RAMP_SECONDS = 0.015

private external class AudioContext() : JsAny {
    val state: String
    val destination: JsAny
    val currentTime: Double
    fun resume(): kotlin.js.Promise<JsAny?>
    fun createBuffer(numberOfChannels: Int, length: Int, sampleRate: Float): AudioBuffer
    fun createBufferSource(): AudioBufferSourceNode
    fun createGain(): GainNode
}

private external class GainNode : JsAny {
    val gain: AudioParam
    fun connect(destination: JsAny): JsAny
}

private external class AudioParam : JsAny {
    var value: Float
    fun setTargetAtTime(target: Float, startTime: Double, timeConstant: Double): AudioParam
}

private external class AudioBuffer : JsAny {
    fun getChannelData(channel: Int): JsAny
}

private external class AudioBufferSourceNode : JsAny {
    var buffer: AudioBuffer?
    var onended: ((JsAny) -> Unit)?
    fun connect(destination: JsAny): JsAny
    fun start()
    fun stop()
}

private fun setFloat(array: JsAny, index: Int, value: Float): Unit = js("array[index] = value")
