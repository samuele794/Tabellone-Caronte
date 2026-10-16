package com.takaotech.departureboard.live

import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.train
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.live.BackgroundDuckTest.Companion.JINGLE
import com.takaotech.departureboard.live.BackgroundDuckTest.Companion.PREPARE
import com.takaotech.departureboard.live.BackgroundDuckTest.Companion.VOICE
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.LoopPlayer
import com.takaotech.departureboard.tts.SoundPlayer
import com.takaotech.departureboard.tts.SpeechEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDuckTest {

    private class FakeSource : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode) =
            RfiBoard(station.name, mode, emptyList(), emptyList())

        override fun close() = Unit
    }

    /** Il lettore impiega [PREPARE] a preparare il jingle, che poi dura [JINGLE]. */
    private val sound = object : SoundPlayer {
        override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) {
            delay(PREPARE)
            onStart()
            delay(JINGLE)
        }

        override fun close() = Unit
    }

    /** La voce dura [VOICE]. */
    private val speech = object : SpeechEngine {
        override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) {
            onStart()
            delay(VOICE)
        }

        override fun close() = Unit
    }

    private object SilentLoop : LoopPlayer {
        override suspend fun loop(path: String, gain: StateFlow<Float>) = Unit
        override fun close() = Unit
    }

    private fun TestScope.setup(jingleLength: Duration? = JINGLE): Pair<Announcer, BackgroundSound> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val announcer = Announcer(
            speech, sound,
            jingle = { ByteArray(1) },
            jingleLength = { jingleLength },
            context = dispatcher,
        )
        val background = BackgroundSound(
            SilentLoop, context = dispatcher, timeSource = testScheduler.timeSource,
        )
        val state = LiveBoardState(
            listOf(FakeSource()).associateBy { it.source },
            announcer = announcer,
            backgroundSound = background,
        )
        // Dopo il tabellone, che alla creazione porta il sottofondo al volume predefinito.
        background.volume = VOLUME
        backgroundScope.launch { state.duckSoundsDuringAnnouncements() }
        runCurrent()
        return announcer to background
    }

    private fun BackgroundSound.position() = sqrt(gain.value)

    private fun Announcer.announceTrain() =
        enqueue(listOf(Announcement.Imminent(train(number = "2162"), BoardMode.Departures)))

    /** Avanza di [time] ed esegue quello che è in programma a quell'istante. */
    private fun TestScope.at(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun `scende col jingle fino al 5 per cento e risale a fine annuncio`() = runTest {
        val (announcer, background) = setup()
        announcer.announceTrain()

        at(PREPARE - 1.milliseconds)
        assertEquals(VOLUME, background.position(), 1e-4f, "mentre il jingle si prepara non scende")

        // Da qui il jingle si sente.
        at(1.milliseconds + JINGLE / 2)
        val half = (VOLUME + BackgroundSound.DUCKED_VOLUME) / 2
        assertEquals(half, background.position(), 0.01f, "a metà jingle, a metà strada")

        at(JINGLE / 2)
        assertEquals(
            BackgroundSound.DUCKED_VOLUME,
            background.position(),
            1e-4f,
            "a fine jingle al 5%"
        )

        at(VOICE - 1.milliseconds)
        assertEquals(
            BackgroundSound.DUCKED_VOLUME,
            background.position(),
            1e-4f,
            "resta giù finché parla la voce"
        )

        // Fine dell'annuncio: risale.
        at(1.milliseconds + BackgroundSound.RESTORE_DURATION / 2)
        assertEquals(half, background.position(), 0.01f, "a metà ritorno, a metà strada")

        at(BackgroundSound.RESTORE_DURATION / 2)
        assertEquals(VOLUME, background.position(), 1e-4f, "di nuovo al volume scelto")

        announcer.close()
        background.close()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `senza durata del jingle scende in fretta`() = runTest {
        val (announcer, background) = setup(jingleLength = null)
        announcer.announceTrain()

        at(PREPARE + BackgroundSound.QUICK_DUCK)
        assertEquals(BackgroundSound.DUCKED_VOLUME, background.position(), 1e-4f)

        announcer.close()
        background.close()
    }

    @Test
    fun `cancellare la coda a metà discesa la fa risalire da lì`() = runTest {
        val (announcer, background) = setup()
        announcer.announceTrain()

        at(PREPARE + JINGLE / 2)
        val reached = background.position()
        announcer.clear()
        runCurrent()

        at(BackgroundSound.RESTORE_DURATION / 2)
        assertEquals((reached + VOLUME) / 2, background.position(), 0.01f)
        at(BackgroundSound.RESTORE_DURATION / 2)
        assertEquals(VOLUME, background.position(), 1e-4f)

        announcer.close()
        background.close()
    }

    private companion object {
        const val VOLUME = 0.8f
        val PREPARE = 500.milliseconds
        val JINGLE = 5.seconds
        val VOICE = 10.seconds
    }
}
