package com.takaotech.departureboard.announce

import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.tts.SoundPlayer
import com.takaotech.departureboard.tts.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * Diffonde gli annunci uno alla volta, nell'ordine in cui arrivano: prima il jingle, poi il testo
 * letto da [speech]. I motori sono interfacce, così ogni piattaforma usa i propri.
 */
class Announcer(
    private val speech: SpeechEngine,
    private val sound: SoundPlayer,
    jingle: suspend () -> ByteArray = { Res.readBytes(JINGLE) },
    context: CoroutineContext = Dispatchers.Default,
    private val onError: (Throwable) -> Unit = { e -> DebugLog.d(TAG) { "Annuncio non riuscito: $e" } },
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + context)
    private val queue = Channel<Pair<Int, Announcement>>(Channel.UNLIMITED)
    private val jingleBytes =
        scope.async { runCatching { jingle() }.onFailure(onError).getOrNull() }

    /** Cresce a ogni [clear]: gli annunci accodati prima vengono scartati. */
    @Volatile
    private var generation = 0

    @Volatile
    private var current: Job? = null

    /** Annunci in diffusione in questo momento (di solito 0 o 1). */
    private val active = MutableStateFlow(0)

    /** true mentre si diffonde un annuncio, jingle compreso. */
    val speaking: Flow<Boolean> = active.map { it > 0 }.distinctUntilChanged()

    private val startedFlow = MutableSharedFlow<Announcement>(extraBufferCapacity = 16)

    /** Ogni annuncio nel momento in cui comincia a sentirsi: col jingle, o con la voce se manca. */
    val started: SharedFlow<Announcement> = startedFlow.asSharedFlow()

    /** Località RFI per sciogliere le abbreviazioni nei testi; si aggiorna quando arriva l'elenco. */
    @Volatile
    var places: PlaceNames = PlaceNames.None

    init {
        scope.launch {
            for ((gen, announcement) in queue) {
                if (gen != generation) {
                    DebugLog.d(TAG) { "Scartato ${announcement.summary()}: coda svuotata" }
                    continue
                }
                val job = launch { if (gen == generation) announce(announcement) }
                current = job
                job.join()
            }
        }
    }

    fun enqueue(announcements: List<Announcement>) {
        val gen = generation
        announcements.forEach {
            DebugLog.d(TAG) { "In coda ${it.summary()}: ${AnnouncementText.text(it, places)}" }
            queue.trySend(gen to it)
        }
    }

    /** Scarta gli annunci in attesa e interrompe quello in corso (es. cambio di stazione). */
    fun clear() {
        DebugLog.d(TAG) { "Coda svuotata, annuncio in corso interrotto" }
        generation++
        current?.cancel()
    }

    /** Diffonde subito [announcement], fuori dalla coda; ritorna a fine lettura. */
    suspend fun announce(announcement: Announcement) {
        val text = AnnouncementText.text(announcement, places)
        active.update { it + 1 }
        try {
            DebugLog.d(TAG) { "Diffondo ${announcement.summary()}: $text" }
            var signalled = false
            val started = {
                if (!signalled) {
                    signalled = true
                    startedFlow.tryEmit(announcement)
                }
            }
            jingleBytes.await()?.let { sound.play(it, started) }
            speech.speak(text, started)
            DebugLog.d(TAG) { "Finito ${announcement.summary()}" }
        } catch (e: CancellationException) {
            DebugLog.d(TAG) { "Interrotto ${announcement.summary()}" }
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            active.update { it - 1 }
        }
    }

    override fun close() {
        queue.close()
        scope.cancel()
        speech.close()
        sound.close()
    }

    companion object {
        const val JINGLE = "files/trenitalia-station-anouncements.mp3"
        const val TAG = "Annunci"
    }
}
