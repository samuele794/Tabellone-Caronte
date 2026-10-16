package com.takaotech.departureboard

import com.takaotech.departureboard.tts.LoopPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSoundTest {

    private class FakeLoop : LoopPlayer {
        val started = CompletableDeferred<Pair<String, StateFlow<Float>>>()
        var stopped = false
        var failure: Exception? = null

        override suspend fun loop(path: String, gain: StateFlow<Float>) {
            started.complete(path to gain)
            failure?.let { throw it }
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `il guadagno è il quadrato del cursore`() {
        val sound = BackgroundSound(FakeLoop())
        assertEquals(1f, sound.gain.value)
        sound.volume = 0.5f
        assertEquals(0.25f, sound.gain.value)
        sound.volume = 3f
        assertEquals(1f, sound.volume)
    }

    /** Posizione del cursore che corrisponde al guadagno attuale. */
    private fun BackgroundSound.position() = sqrt(gain.value)

    private fun TestScope.sound() = BackgroundSound(
        FakeLoop(),
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    @Test
    fun `duck scende in linea retta fino alla posizione ridotta nel tempo dato`() = runTest {
        val sound = sound()
        sound.volume = 0.85f
        sound.duck(4.seconds)
        runCurrent()
        assertEquals(0.85f, sound.position(), 1e-3f)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0.65f, sound.position(), 0.01f)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0.45f, sound.position(), 0.01f)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(BackgroundSound.DUCKED_VOLUME, sound.position(), 1e-4f)
        assertEquals(1f, sound.ducking)
        sound.close()
    }

    @Test
    fun `restore risale dal punto in cui è arrivata la discesa`() = runTest {
        val sound = sound()
        sound.volume = 0.85f
        sound.duck(4.seconds)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0.5f, sound.ducking, 0.02f)

        // Annuncio interrotto a metà discesa: risale da lì, in tutto il tempo del ritorno.
        sound.restore(2.seconds)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0.25f, sound.ducking, 0.02f)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0f, sound.ducking)
        assertEquals(0.85f, sound.position(), 1e-4f)
        sound.close()
    }

    @Test
    fun `cambiare volume da abbassato sposta solo il punto di partenza`() = runTest {
        val sound = sound()
        sound.volume = 0.8f
        sound.duck(1.seconds)
        advanceTimeBy(2.seconds)
        runCurrent()
        sound.volume = 0.4f
        assertEquals(BackgroundSound.DUCKED_VOLUME, sound.position(), 1e-4f)

        // Un volume già sotto la posizione ridotta non scende oltre.
        sound.volume = 0.01f
        assertEquals(0.01f, sound.position(), 1e-4f)
        sound.close()
    }

    @Test
    fun `suona la traccia in loop finché non viene cancellata`() = runTest {
        val loop = FakeLoop()
        val sound = BackgroundSound(loop)
        val playing = launch { sound.play() }
        val (path, gain) = loop.started.await()
        assertEquals(BackgroundSound.TRACK, path)
        assertSame(sound.gain, gain)
        playing.cancel()
        playing.join()
        assertEquals(true, loop.stopped)
    }

    @Test
    fun `un errore del player non esce da play`() = runTest {
        val loop = FakeLoop().apply { failure = IllegalStateException("guasto") }
        BackgroundSound(loop).play()
        assertEquals(true, loop.started.isCompleted)
    }
}
