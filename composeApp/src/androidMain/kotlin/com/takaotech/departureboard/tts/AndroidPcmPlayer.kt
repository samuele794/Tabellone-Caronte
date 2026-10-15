package com.takaotech.departureboard.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** PCM con un [AudioTrack] statico per riproduzione: si carica tutto e parte subito. */
class AndroidPcmPlayer : PcmPlayer {

    override suspend fun play(samples: ShortArray, sampleRate: Int, gain: StateFlow<Float>) {
        if (samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.setVolume(gain.value)
            track.play()
            coroutineScope {
                // Il volume del track segue il guadagno anche a riproduzione iniziata.
                val volume = launch { gain.collect { track.setVolume(it) } }
                // Un track statico non avvisa a fine riproduzione: si attende la sua durata.
                delay(samples.size * 1_000L / sampleRate + 50)
                volume.cancel()
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    override fun close() {}
}
