package tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import debuglog.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Sintesi vocale di sistema di Android ([TextToSpeech]), in italiano.
 *
 * Se il motore non inizia a parlare (su alcuni dispositivi, come l'emulatore arm64, il motore
 * Google va in crash nativo sintetizzando in locale) l'istanza di [TextToSpeech] viene ricreata
 * e si riprova con una voce di rete della stessa lingua.
 *
 * [voiceName] forza una voce del motore (es. "it-it-x-kda-network"); null = quella predefinita
 * per [locale]. Voci disponibili ed esito di ogni lettura finiscono nel log (tag `AndroidTts`).
 */
class AndroidSpeechEngine(
    context: Context,
    private val locale: Locale = Locale.ITALY,
    private val voiceName: String? = null,
) : SpeechEngine {

    private val context = context.applicationContext
    private val ids = AtomicInteger()
    private val mutex = Mutex()

    /** Motore attuale; null finché non serve o dopo un guasto. */
    private var engine: Engine? = null

    /** Voce di rete scelta dopo un guasto; resta valida quando il motore viene ricreato. */
    @Volatile
    private var fallbackVoice: String? = null

    override suspend fun speak(text: String) {
        val first = mutex.withLock {
            engine ?: newEngine(fallbackVoice ?: voiceName).also { engine = it }
        }
        try {
            first.speak(text)
        } catch (e: NotStartedException) {
            val retry = mutex.withLock {
                val network = first.networkVoice()?.name ?: fallbackVoice
                if (network == null || network == first.voiceName) {
                    // Il prossimo annuncio riparte da un motore nuovo invece che da uno guasto.
                    if (engine === first) engine = null
                    first.close()
                    throw e
                }
                log { "${e.message}: ricreo il motore con la voce di rete $network" }
                fallbackVoice = network
                first.close()
                newEngine(network).also { engine = it }
            }
            try {
                retry.speak(text)
            } catch (e2: NotStartedException) {
                mutex.withLock { if (engine === retry) engine = null }
                retry.close()
                throw e2
            }
        }
    }

    /** Come prima della correzione, [TextToSpeech] nasce sul main thread. */
    private suspend fun newEngine(voice: String?) = withContext(Dispatchers.Main) { Engine(voice) }

    override fun close() {
        engine?.close()
        engine = null
    }

    /** Un'istanza di [TextToSpeech] con la voce [voiceName] (null = la predefinita della lingua). */
    private inner class Engine(val voiceName: String?) {
        private val ready = CompletableDeferred<Boolean>()
        private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        /** Voci della lingua, lette a motore collegato: dopo un crash `tts.voices` resta vuoto. */
        @Volatile
        private var voices: List<Voice> = emptyList()

        @Volatile
        private var prepared = false

        private val tts = TextToSpeech(context) { status ->
            log { "init: ${if (status == TextToSpeech.SUCCESS) "ok" else "errore $status"}" }
            ready.complete(status == TextToSpeech.SUCCESS)
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    log { "$utteranceId: iniziato" }
                    started.remove(utteranceId)?.complete(Unit)
                }

                override fun onDone(utteranceId: String) {
                    log { "$utteranceId: finito" }
                    pending.remove(utteranceId)?.complete(Unit)
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    log { "$utteranceId: fermato (interrupted=$interrupted)" }
                    pending.remove(utteranceId)?.complete(Unit)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    log { "$utteranceId: errore $errorCode" }
                    val e = IllegalStateException("TextToSpeech: errore $errorCode")
                    started.remove(utteranceId)?.completeExceptionally(e)
                    pending.remove(utteranceId)?.completeExceptionally(e)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)
            })
        }

        private suspend fun prepare() {
            val ok = withTimeoutOrNull(INIT_TIMEOUT) { ready.await() }
                ?: throw NotStartedException("TextToSpeech non inizializzato dopo $INIT_TIMEOUT")
            check(ok) { "TextToSpeech non disponibile" }
            if (prepared) return
            val result = tts.setLanguage(locale)
            check(result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                "Voce $locale non installata"
            }
            voices = tts.voices.orEmpty().filter { it.locale.language == locale.language }
            voiceName?.let { name ->
                val voice =
                    voices.firstOrNull { it.name == name } ?: error("Voce $name non disponibile")
                check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Impossibile usare la voce $name" }
            }
            log {
                "setLanguage($locale) = $result, motore ${tts.defaultEngine}, voce ${tts.voice?.name}\n" +
                        voices.sortedBy { it.name }.joinToString("\n") {
                            "  voce ${it.name} (${if (it.isNetworkConnectionRequired) "rete" else "locale"}, " +
                                    "qualità ${it.quality}, installata ${KEY_NOT_INSTALLED !in it.features})"
                        }
            }
            prepared = true
        }

        /** Legge [text] e ritorna a fine lettura; [NotStartedException] se il motore non parte. */
        suspend fun speak(text: String) {
            prepare()
            val id = "annuncio-${ids.incrementAndGet()}"
            val start = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            started[id] = start
            pending[id] = done
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            log { "$id: speak = $result, voce ${voiceName ?: "predefinita"}" }
            if (result != TextToSpeech.SUCCESS) {
                started.remove(id)
                pending.remove(id)
                throw NotStartedException("$id: TextToSpeech non ha accettato il testo ($result)")
            }
            try {
                // Se il motore non parte mai la coda degli annunci resterebbe ferma per sempre.
                withTimeoutOrNull(START_TIMEOUT) { start.await() }
                    ?: throw NotStartedException("$id: il motore non ha iniziato a parlare dopo $START_TIMEOUT")
                done.await()
            } finally {
                started.remove(id)
                // Annuncio interrotto (cambio stazione, chiusura, timeout): si ferma la voce.
                if (pending.remove(id) != null) runCatching { tts.stop() }
            }
        }

        /** Voce di rete installata della lingua, preferendo il paese di [locale]. */
        fun networkVoice(): Voice? =
            voices.filter { it.isNetworkConnectionRequired && KEY_NOT_INSTALLED !in it.features }
                .minByOrNull { if (it.locale.country == locale.country) 0 else 1 }

        fun close() {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    private class NotStartedException(message: String) : IllegalStateException(message)

    private fun log(message: () -> String) = DebugLog.d(TAG, message)

    private companion object {
        const val TAG = "AndroidTts"
        const val KEY_NOT_INSTALLED = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
        val INIT_TIMEOUT = 15.seconds
        val START_TIMEOUT = 15.seconds
    }
}
