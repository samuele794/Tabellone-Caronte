package com.takaotech.departureboard.announce

import com.takaotech.departureboard.rfi.BoardMode.Departures
import com.takaotech.departureboard.tts.SoundPlayer
import com.takaotech.departureboard.tts.SpeechEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncerTest {

    private val log = mutableListOf<String>()

    /**
     * Engine finti: registrano le chiamate. Se [gate] è impostato la voce, appena cominciata,
     * aspetta lo sblocco; se lo è [jingleGate], il jingle appena cominciato.
     */
    private var gate: CompletableDeferred<Unit>? = null
    private var jingleGate: CompletableDeferred<Unit>? = null
    private val volumes = mutableListOf<String>()
    private val speech = object : SpeechEngine {
        override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) {
            onStart()
            volumes += "voce $volume"
            gate?.await()
            log += "voce: ${text.substringBefore(',')}"
        }

        override fun close() {
            log += "chiuso"
        }
    }
    private val sound = object : SoundPlayer {
        override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) {
            onStart()
            volumes += "jingle $volume"
            jingleGate?.await()
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
    fun `speaking vale true solo mentre si diffonde`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler)
        )
        val seen = mutableListOf<Boolean>()
        val watch = launch { announcer.speaking.collect { seen += it } }
        gate = CompletableDeferred()
        announcer.enqueue(listOf(announcement("84")))
        advanceUntilIdle()
        assertEquals(true, announcer.speaking.first())

        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, announcer.speaking.first())

        // Anche un annuncio interrotto rialza il volume.
        gate = CompletableDeferred()
        announcer.enqueue(listOf(announcement("753")))
        advanceUntilIdle()
        announcer.clear()
        advanceUntilIdle()
        assertEquals(listOf(false, true, false, true, false), seen)
        watch.cancel()
        announcer.close()
    }

    @Test
    fun `started segnala ogni annuncio quando comincia il jingle, una volta sola e in ordine`() =
        runTest {
            val announcer = Announcer(
                speech,
                sound,
                jingle = { ByteArray(1) },
                context = StandardTestDispatcher(testScheduler)
            )
            val started = mutableListOf<String>()
            val watch = launch { announcer.started.collect { started += it.train.number } }
            advanceUntilIdle()
            jingleGate = CompletableDeferred()
            gate = CompletableDeferred()
            announcer.enqueue(listOf(announcement("84"), announcement("753")))
            advanceUntilIdle()
            assertEquals(listOf("84"), started, "segnalato appena parte il jingle")

            jingleGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf("84"),
                started,
                "la voce non lo segnala di nuovo; il secondo aspetta"
            )

            gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("84", "753"), started)
            watch.cancel()
            announcer.close()
        }

    @Test
    fun `il volume arriva a jingle e voce, dall'annuncio seguente`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { ByteArray(1) },
            context = StandardTestDispatcher(testScheduler)
        )
        announcer.enqueue(listOf(announcement("84")))
        advanceUntilIdle()
        announcer.volume = 0.4f
        announcer.enqueue(listOf(announcement("753")))
        advanceUntilIdle()
        assertEquals(listOf("jingle 1.0", "voce 1.0", "jingle 0.4", "voce 0.4"), volumes)
        announcer.close()
    }

    @Test
    fun `senza jingle started arriva quando comincia la voce`() = runTest {
        val announcer = Announcer(
            speech,
            sound,
            jingle = { error("jingle mancante") },
            context = StandardTestDispatcher(testScheduler),
            onError = {},
        )
        val started = mutableListOf<String>()
        val watch = launch { announcer.started.collect { started += it.train.number } }
        advanceUntilIdle()
        announcer.enqueue(listOf(announcement("84")))
        advanceUntilIdle()
        assertEquals(listOf("84"), started)
        assertEquals(listOf("voce: Il treno Alta Velocità ottantaquattro di Trenitalia"), log)
        watch.cancel()
        announcer.close()
    }

    @Test
    fun `un errore del motore non ferma i successivi`() = runTest {
        val errors = mutableListOf<Throwable>()
        val broken = object : SpeechEngine {
            var calls = 0
            override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) {
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
