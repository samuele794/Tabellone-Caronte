package announce

import debuglog.DebugLog
import departureboard.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import tts.SoundPlayer
import tts.SpeechEngine
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
        try {
            DebugLog.d(TAG) { "Diffondo ${announcement.summary()}: $text" }
            jingleBytes.await()?.let { sound.play(it) }
            speech.speak(text)
            DebugLog.d(TAG) { "Finito ${announcement.summary()}" }
        } catch (e: CancellationException) {
            DebugLog.d(TAG) { "Interrotto ${announcement.summary()}" }
            throw e
        } catch (e: Exception) {
            onError(e)
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
