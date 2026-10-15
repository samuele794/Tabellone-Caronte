package com.takaotech.departureboard

import com.takaotech.departureboard.tts.PcmPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlapClatterTest {

    private val rate = 44_100

    /** Tre colpi finti da 30 ms: un'onda smorzata a piena ampiezza. */
    private val clicks = FlapClicks(rate, List(3) { k ->
        FloatArray(rate * 30 / 1000) { i -> sin(i * (0.3f + 0.1f * k)) * exp(-i / 200f) * 0.9f }
    })

    @Test
    fun `senza girate nessun suono`() {
        assertEquals(0, mixClatter(IntArray(0), clicks).size)
    }

    @Test
    fun `la traccia arriva fino all'ultima girata e alla sua coda`() {
        val samples = mixClatter(intArrayOf(0, 500, 1_000), clicks, random = Random(1))
        val seconds = samples.size.toDouble() / rate
        assertTrue(seconds > 1.0 && seconds < 1.1, "durata $seconds s")
    }

    @Test
    fun `un colpo si sente dove cade la girata`() {
        val samples = mixClatter(intArrayOf(200), clicks, random = Random(1))
        val before = samples.take(200 * rate / 1000 - 10).maxOf { abs(it.toInt()) }
        val at = samples.drop(200 * rate / 1000).take(rate / 100).maxOf { abs(it.toInt()) }
        assertEquals(0, before)
        assertTrue(at > 1_000, "picco $at")
    }

    @Test
    fun `un tabellone intero che gira non satura`() {
        // 1400 palette, ognuna fino a 40 giri da 55 ms con partenza sfasata.
        val random = Random(7)
        val events = IntArray(1_400 * 40) { i -> random.nextInt(250) + (i % 40 + 1) * 55 }
        val samples = mixClatter(events, clicks, random = Random(3))
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue(peak < Short.MAX_VALUE * 0.95, "picco $peak")
    }

    @Test
    fun `con lo stesso seme la traccia è la stessa`() {
        val events = intArrayOf(0, 10, 15, 300)
        assertContentEquals(
            mixClatter(events, clicks, random = Random(42)),
            mixClatter(events, clicks, random = Random(42)),
        )
    }

    @Test
    fun `durante un annuncio il volume scende al 20 per cento, se non è già più basso`() {
        FlapClatter(SilentPlayer, clicks = { ByteArray(0) }).use { clatter ->
            assertEquals(1f, clatter.gain.value)
            clatter.ducked = true
            assertEquals(0.04f, clatter.gain.value, 1e-6f)
            clatter.ducked = false
            clatter.volume = 0.5f
            assertEquals(0.25f, clatter.gain.value, 1e-6f)
            clatter.volume = 0.1f
            clatter.ducked = true
            assertEquals(0.01f, clatter.gain.value, 1e-6f)
        }
    }

    @Test
    fun `un crepitio già partito segue il volume dell'annuncio`() = runBlocking {
        val playing = CompletableDeferred<StateFlow<Float>>()
        val player = object : PcmPlayer {
            override suspend fun play(
                samples: ShortArray,
                sampleRate: Int,
                gain: StateFlow<Float>
            ) {
                playing.complete(gain)
            }

            override fun close() {}
        }
        val bank = ShortArray(rate * 30 / 1000) { (it % 100 * 100).toShort() }
        FlapClatter(player, clicks = { wav(bank, rate) }).use { clatter ->
            clatter.schedule(intArrayOf(0, 55, 110))
            val gain = withTimeout(5_000) { playing.await() }
            assertEquals(1f, gain.value)
            clatter.ducked = true
            assertEquals(0.04f, gain.value, 1e-6f)
        }
    }

    private object SilentPlayer : PcmPlayer {
        override suspend fun play(samples: ShortArray, sampleRate: Int, gain: StateFlow<Float>) =
            Unit

        override fun close() = Unit
    }

    @Test
    fun `una WAV a slot si divide in colpi`() {
        val samples = ShortArray(3 * 441 + 100) { (it % 441).toShort() }
        val bank = FlapClicks.fromWav(wav(samples, rate), slotMillis = 10)
        assertEquals(rate, bank.sampleRate)
        assertEquals(3, bank.clicks.size)
        assertEquals(441, bank.clicks[1].size)
        assertEquals(5 / 32_768f, bank.clicks[2][5])
    }

    @Test
    fun `una WAV stereo non si accetta`() {
        assertFailsWith<IllegalArgumentException> {
            FlapClicks.fromWav(wav(ShortArray(100), rate, channels = 2))
        }
    }

    @Test
    fun `i colpi del tabellone si leggono`() {
        val bytes = File("src/commonMain/composeResources/files/flap-clicks.wav").readBytes()
        val bank = FlapClicks.fromWav(bytes)
        assertEquals(44_100, bank.sampleRate)
        assertEquals(24, bank.clicks.size)
        bank.clicks.forEach { click -> assertTrue(click.maxOf { abs(it) } > 0.5f) }
    }

    private fun wav(samples: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        fun u32(v: Int) {
            u16(v and 0xFFFF); u16(v ushr 16)
        }
        out.write("RIFF".toByteArray()); u32(36 + samples.size * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16)
        u16(1); u16(channels); u32(rate); u32(rate * 2 * channels); u16(2 * channels); u16(16)
        out.write("data".toByteArray()); u32(samples.size * 2)
        samples.forEach { u16(it.toInt()) }
        return out.toByteArray()
    }
}
