package announce

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import rfi.BoardMode.Departures
import tts.SoundPlayer
import tts.SpeechEngine
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncerTest {

    private val log = mutableListOf<String>()

    /** Engine finti: registrano le chiamate; se [gate] è impostato la voce aspetta lo sblocco. */
    private var gate: CompletableDeferred<Unit>? = null
    private val speech = object : SpeechEngine {
        override suspend fun speak(text: String) {
            gate?.await()
            log += "voce: ${text.substringBefore(',')}"
        }

        override fun close() {
            log += "chiuso"
        }
    }
    private val sound = object : SoundPlayer {
        override suspend fun play(bytes: ByteArray) {
            log += "jingle ${bytes.size}"
        }

        override fun close() {}
    }

    private fun announcement(number: String) =
        Announcement.Imminent(train(number = number), Departures)

    @Test
    fun `jingle prima di ogni annuncio, in ordine`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(3) },
            context = StandardTestDispatcher(testScheduler)
        )
        announcer.enqueue(listOf(announcement("84"), announcement("753")))
        advanceUntilIdle()
        assertEquals(
            listOf(
                "jingle 3", "voce: Il treno Alta Velocità ottantaquattro di Trenitalia",
                "jingle 3", "voce: Il treno Alta Velocità sette cinque tre di Trenitalia",
            ),
            log,
        )
        announcer.close()
    }

    @Test
    fun `clear scarta la coda e interrompe l'annuncio in corso`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler)
        )
        gate = CompletableDeferred()
        announcer.enqueue(listOf(announcement("84"), announcement("753")))
        advanceUntilIdle()
        assertEquals(listOf("jingle 1"), log, "la voce del primo è in corso")

        announcer.clear()
        gate = null
        announcer.enqueue(listOf(announcement("12156")))
        advanceUntilIdle()
        assertEquals(
            listOf(
                "jingle 1",
                "jingle 1",
                "voce: Il treno Alta Velocità dodici uno cinque sei di Trenitalia"
            ), log
        )
        announcer.close()
        assertEquals("chiuso", log.last())
    }

    @Test
    fun `un errore del motore non ferma i successivi`() = runTest {
        val errors = mutableListOf<Throwable>()
        val broken = object : SpeechEngine {
            var calls = 0
            override suspend fun speak(text: String) {
                if (calls++ == 0) error("voce non installata")
                log += "voce ok"
            }

            override fun close() {}
        }
        val announcer = Announcer(
            broken,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler),
            onError = { errors += it })
        announcer.enqueue(listOf(announcement("84"), announcement("753")))
        advanceUntilIdle()
        assertEquals("voce non installata", errors.single().message)
        assertEquals(listOf("jingle 1", "jingle 1", "voce ok"), log)
        announcer.close()
    }
}
