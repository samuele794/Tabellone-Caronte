package com.takaotech.departureboard

import com.takaotech.departureboard.QuietHoursTest.Companion.VOLUME
import com.takaotech.departureboard.tts.LoopPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class QuietHoursTest {

    /** Lettore finto: dice se sta suonando e quante volte è partito. */
    private class FakeLoop : LoopPlayer {
        var playing = false
        var starts = 0

        override suspend fun loop(path: String, gain: StateFlow<Float>) {
            playing = true
            starts++
            try {
                awaitCancellation()
            } finally {
                playing = false
            }
        }

        override fun close() = Unit
    }

    private fun BackgroundSound.position() = sqrt(gain.value)

    /**
     * Sottofondo a [VOLUME] che comincia a suonare alle [start], con l'orologio che avanza col
     * tempo virtuale del test.
     */
    private fun TestScope.startAt(start: LocalTime): Pair<BackgroundSound, FakeLoop> {
        val loop = FakeLoop()
        val sound = BackgroundSound(
            loop,
            context = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        ).apply { volume = VOLUME }
        val origin = start.toNanosecondOfDay()
        val now = {
            val nanos = origin + testScheduler.currentTime.milliseconds.inWholeNanoseconds
            LocalTime.fromNanosecondOfDay(nanos % NANOS_PER_DAY)
        }
        backgroundScope.launch { sound.playOutsideQuietHours(now) }
        runCurrent()
        return sound to loop
    }

    private fun TestScope.at(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun `all'una sfuma e si ferma, alle cinque riparte sfumando`() = runTest {
        val (sound, loop) = startAt(LocalTime(0, 59, 50))
        assertTrue(loop.playing)
        assertEquals(VOLUME, sound.position(), 1e-4f, "prima dell'una suona pieno, senza sfumare")

        at(10.seconds + BackgroundSound.FADE / 2)
        assertTrue(loop.playing, "mentre sfuma suona ancora")
        assertEquals(VOLUME / 2, sound.position(), 0.01f, "a metà sfumatura, a metà volume")

        at(BackgroundSound.FADE / 2)
        assertFalse(loop.playing, "a fine sfumatura si ferma")
        assertEquals(0f, sound.gain.value)

        // Fino alle 5 resta fermo, qualunque cosa succeda.
        at(4.hours - BackgroundSound.FADE - 1.milliseconds)
        assertFalse(loop.playing)
        assertEquals(1, loop.starts)

        at(1.milliseconds)
        assertTrue(loop.playing, "alle 5 riparte")
        assertEquals(2, loop.starts)
        assertEquals(0f, sound.position(), 1e-4f, "e parte da muto")

        at(BackgroundSound.FADE / 2)
        assertEquals(VOLUME / 2, sound.position(), 0.01f)
        at(BackgroundSound.FADE / 2)
        assertEquals(VOLUME, sound.position(), 1e-4f, "fino al volume scelto")
        sound.close()
    }

    @Test
    fun `avviato di notte non suona fino alle cinque`() = runTest {
        val (sound, loop) = startAt(LocalTime(3, 0))
        assertFalse(loop.playing)

        at(2.hours - 1.milliseconds)
        assertFalse(loop.playing)
        at(1.milliseconds)
        assertTrue(loop.playing)
        sound.close()
    }

    @Test
    fun `le ore di silenzio vanno dall'una incluse alle cinque escluse`() {
        assertFalse(BackgroundSound.isQuiet(LocalTime(0, 59, 59)))
        assertTrue(BackgroundSound.isQuiet(LocalTime(1, 0)))
        assertTrue(BackgroundSound.isQuiet(LocalTime(4, 59, 59)))
        assertFalse(BackgroundSound.isQuiet(LocalTime(5, 0)))
    }

    @Test
    fun `il tempo fino a un'ora passa dalla mezzanotte`() {
        assertEquals(4.hours, BackgroundSound.timeUntil(LocalTime(1, 0), LocalTime(5, 0)))
        assertEquals(20.hours, BackgroundSound.timeUntil(LocalTime(5, 0), LocalTime(1, 0)))
        assertEquals(10.minutes, BackgroundSound.timeUntil(LocalTime(0, 50), LocalTime(1, 0)))
        assertEquals(Duration.ZERO, BackgroundSound.timeUntil(LocalTime(1, 0), LocalTime(1, 0)))
    }

    private companion object {
        const val VOLUME = 0.8f
        val NANOS_PER_DAY = 24.hours.inWholeNanoseconds
    }
}
