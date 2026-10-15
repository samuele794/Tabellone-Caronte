package com.takaotech.departureboard.live

import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.board
import com.takaotech.departureboard.announce.train
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
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
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncedRowBlinkTest {

    private val milano = Station("1728", "MILANO CENTRALE")

    private class FakeSource : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard =
            board(train(number = "9915"), train(number = "2162"), train(number = "84"))

        override fun close() = Unit
    }

    /** La voce dura 20 secondi. */
    private val speech = object : SpeechEngine {
        override suspend fun speak(text: String, onStart: () -> Unit) {
            onStart()
            delay(20_000)
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

    /** Fa partire un annuncio del 2162 e attende che il jingle cominci a sentirsi. */
    private suspend fun TestScope.announceAndWaitForJingle(
        state: LiveBoardState,
        announcer: Announcer
    ) {
        state.refresh()
        launch { state.blinkAnnouncedRows() }
        runCurrent()
        assertEquals(state.departures, state.boardDepartures, "nessun annuncio, niente lampeggio")

        announcer.enqueue(
            listOf(
                Announcement.Imminent(
                    train(number = "2162"),
                    BoardMode.Departures
                )
            )
        )
        advanceTimeBy(PREPARE_MILLIS - 1)
        assertNull(state.announcedTrain, "mentre il lettore prepara il jingle non lampeggia")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("2162", state.announcedTrain)
        assertEquals(listOf("2162"), state.boardDepartures.filter { it.blink }.map { it.number })
    }

    private suspend fun TestScope.assertBlinksFor(state: LiveBoardState, duration: Duration) {
        advanceTimeBy(duration.inWholeMilliseconds - 1)
        assertEquals("2162", state.announcedTrain)
        advanceTimeBy(2)
        assertNull(state.announcedTrain, "dopo $duration smette, anche se l'annuncio continua")
        assertEquals(state.departures, state.boardDepartures)
    }

    @Test
    fun `la riga del treno annunciato lampeggia da quando comincia il jingle`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler),
        )
        val state = LiveBoardState(
            mapOf(StationSource.Rfi to FakeSource()), initial = milano, announcer = announcer,
        )
        announceAndWaitForJingle(state, announcer)
        assertBlinksFor(state, LiveBoardState.DEFAULT_ANNOUNCE_BLINK)
        announcer.close()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `il lampeggio dura quanto scelto nelle impostazioni`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler),
        )
        val state = LiveBoardState(
            mapOf(StationSource.Rfi to FakeSource()), initial = milano, announcer = announcer,
        )
        state.announceBlink = 8.seconds
        announceAndWaitForJingle(state, announcer)
        assertBlinksFor(state, 8.seconds)
        announcer.close()
        coroutineContext.cancelChildren()
    }

    private companion object {
        const val PREPARE_MILLIS = 500L
        const val JINGLE_MILLIS = 5_000L
    }
}
