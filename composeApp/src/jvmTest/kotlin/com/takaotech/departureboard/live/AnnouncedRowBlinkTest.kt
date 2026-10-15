package com.takaotech.departureboard.live

import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.board
import com.takaotech.departureboard.announce.train
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.SoundPlayer
import com.takaotech.departureboard.tts.SpeechEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncedRowBlinkTest {

    private val milano = Station("1728", "MILANO CENTRALE")

    /** Tabellone che mostra [trains]: si cambia tra un aggiornamento e l'altro. */
    private class FakeSource(var trains: List<RfiTrain>) : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard =
            board(*trains.toTypedArray())

        override fun close() = Unit
    }

    /** La voce dura un minuto: più di qualsiasi lampeggio. */
    private val speech = object : SpeechEngine {
        override suspend fun speak(text: String, onStart: () -> Unit) {
            onStart()
            delay(60_000)
        }

        override fun close() = Unit
    }

    /** Il lettore impiega mezzo secondo a preparare il jingle, che dura 5 secondi come quello vero. */
    private val sound = object : SoundPlayer {
        override suspend fun play(bytes: ByteArray, onStart: () -> Unit) {
            delay(PREPARE_MILLIS)
            onStart()
            delay(JINGLE_MILLIS)
        }

        override fun close() = Unit
    }

    private fun TestScope.announcer() = Announcer(
        speech, sound, jingle = { ByteArray(1) }, context = StandardTestDispatcher(testScheduler),
    )

    private fun LiveBoardState.blinking() = boardDepartures.filter { it.blink }.map { it.number }

    /** Fa partire un annuncio del 2162 e attende che il jingle cominci a sentirsi. */
    private suspend fun TestScope.announceAndWaitForJingle(state: LiveBoardState, announcer: Announcer) {
        state.refresh()
        launch { state.blinkAnnouncedRows() }
        runCurrent()
        assertEquals(state.departures, state.boardDepartures, "nessun annuncio, niente lampeggio")

        announcer.enqueue(listOf(Announcement.Imminent(train(number = "2162"), BoardMode.Departures)))
        advanceTimeBy(PREPARE_MILLIS - 1)
        assertEquals(emptySet(), state.blinkingTrains, "mentre il lettore prepara il jingle non lampeggia")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(setOf("2162"), state.blinkingTrains)
        assertEquals(listOf("2162"), state.blinking())
    }

    private suspend fun TestScope.assertBlinksFor(
        state: LiveBoardState,
        duration: Duration,
        trains: Set<String> = setOf("2162"),
    ) {
        advanceTimeBy(duration.inWholeMilliseconds - 1)
        assertEquals(trains, state.blinkingTrains)
        advanceTimeBy(2)
        assertEquals(emptySet(), state.blinkingTrains, "dopo $duration smette")
        assertEquals(state.departures, state.boardDepartures)
    }

    private val trains = listOf(train(number = "9915"), train(number = "2162"), train(number = "84"))

    @Test
    fun `la riga del treno annunciato lampeggia da quando comincia il jingle`() = runTest {
        val announcer = announcer()
        val state = LiveBoardState(
            mapOf(StationSource.Rfi to FakeSource(trains)), initial = milano, announcer = announcer,
        )
        announceAndWaitForJingle(state, announcer)
        assertBlinksFor(state, LiveBoardState.DEFAULT_ANNOUNCE_BLINK)
        announcer.close()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `il lampeggio dura quanto scelto nelle impostazioni`() = runTest {
        val announcer = announcer()
        val state = LiveBoardState(
            mapOf(StationSource.Rfi to FakeSource(trains)), initial = milano, announcer = announcer,
        )
        state.announceBlink = 25.seconds
        announceAndWaitForJingle(state, announcer)
        assertBlinksFor(state, 25.seconds)
        announcer.close()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `con gli annunci sonori spenti le righe variate lampeggiano subito, insieme`() = runTest {
        val announcer = announcer()
        val source = FakeSource(trains)
        val state = LiveBoardState(
            mapOf(StationSource.Rfi to source), initial = milano, announcer = announcer,
        )
        state.announce = false
        launch { state.blinkAnnouncedRows() }
        state.refresh()
        runCurrent()
        assertEquals(emptySet(), state.blinkingTrains, "il primo tabellone fa solo da base")

        // Il 2162 cambia binario e il 84 ha le spie accese.
        source.trains = listOf(
            train(number = "9915"),
            train(number = "2162", platform = "4"),
            train(number = "84", flashing = true),
        )
        state.refresh()
        runCurrent()
        assertEquals(setOf("2162", "84"), state.blinkingTrains)
        assertEquals(listOf("2162", "84"), state.blinking())
        assertBlinksFor(state, LiveBoardState.DEFAULT_ANNOUNCE_BLINK, setOf("2162", "84"))
        announcer.close()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `senza motore per gli annunci le righe variate lampeggiano lo stesso`() = runTest {
        val source = FakeSource(trains)
        val state = LiveBoardState(mapOf(StationSource.Rfi to source), initial = milano)
        launch { state.blinkAnnouncedRows() }
        state.refresh()
        source.trains = listOf(train(number = "9915", platform = "2"), train(number = "2162"))
        state.refresh()
        runCurrent()
        assertEquals(setOf("9915"), state.blinkingTrains)
        assertBlinksFor(state, LiveBoardState.DEFAULT_ANNOUNCE_BLINK, setOf("9915"))
        coroutineContext.cancelChildren()
    }

    private companion object {
        const val PREPARE_MILLIS = 500L
        const val JINGLE_MILLIS = 5_000L
    }
}
