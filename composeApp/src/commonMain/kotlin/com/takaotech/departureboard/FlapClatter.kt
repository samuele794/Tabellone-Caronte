package com.takaotech.departureboard

import androidx.compose.runtime.staticCompositionLocalOf
import com.takaotech.departureboard.FlapClatter.Companion.DUCKED_VOLUME
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.tts.PcmPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.math.tanh
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Crepitio delle palette: raccoglie le girate che partono insieme (tutte le palette cambiate
 * nello stesso aggiornamento) in un'unica traccia, ci mette un colpo registrato per ogni girata
 * ([mixClatter]) e la suona con [player]. Le tracce di gruppi diversi si sovrappongono.
 *
 * I colpi arrivano da [clicks], una WAV letta una volta sola ([FlapClicks.fromWav]): di serie
 * quella in `composeResources/files`.
 */
class FlapClatter(
    private val player: PcmPlayer,
    clicks: suspend () -> ByteArray = { Res.readBytes(CLICKS) },
    context: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + context)

    private val bank = scope.async {
        runCatching { FlapClicks.fromWav(clicks()) }
            .onFailure { e -> DebugLog.d(TAG) { "Colpi delle palette non disponibili: $e" } }
            .getOrNull()
    }

    /**
     * Volume del crepitio, da 0 (muto) a 1 (pieno), come la posizione di un cursore: il guadagno
     * è il quadrato, così metà cursore suona circa a metà forza percepita e non quasi pieno.
     */
    @Volatile
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateGain()
        }

    /** Durante un annuncio: il volume scende al più a [DUCKED_VOLUME], anche a crepitio in corso. */
    @Volatile
    var ducked: Boolean = false
        set(value) {
            field = value
            updateGain()
        }

    private val _gain = MutableStateFlow(1f)

    /** Guadagno che i player applicano adesso, da [volume] e [ducked]. */
    val gain: StateFlow<Float> = _gain.asStateFlow()

    private fun updateGain() {
        val position = if (ducked) minOf(volume, DUCKED_VOLUME) else volume
        _gain.value = position * position
    }

    /** Girate da suonare: istante della richiesta e millisecondi di ogni girata da lì. */
    private val pending =
        Channel<Pair<TimeSource.Monotonic.ValueTimeMark, IntArray>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (first in pending) {
                // Le palette cambiate nella stessa ricomposizione arrivano a pochi ms di distanza.
                delay(BATCH_MILLIS)
                val batch = mutableListOf(first)
                while (true) batch += pending.tryReceive().getOrNull() ?: break
                val origin = first.first
                val events = batch.flatMap { (mark, offsets) ->
                    val shift = (mark - origin).inWholeMilliseconds.toInt()
                    offsets.map { it + shift }
                }.toIntArray()
                launch { play(events) }
            }
        }
    }

    /** Girate che partono adesso, [offsetsMillis] ms da ora (una voce per ogni palette che cade). */
    fun schedule(offsetsMillis: IntArray) {
        if (offsetsMillis.isNotEmpty()) pending.trySend(TimeSource.Monotonic.markNow() to offsetsMillis)
    }

    /** Breve crepitio per sentire il [volume] appena scelto: qualche palette di fila. */
    fun preview() {
        schedule(IntArray(PREVIEW_FLIPS) { it * PREVIEW_STEP_MILLIS })
    }

    private suspend fun play(events: IntArray) {
        try {
            val clicks = bank.await() ?: return
            val samples = withContext(Dispatchers.Default) { mixClatter(events, clicks) }
            player.play(samples, clicks.sampleRate, gain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG) { "Crepitio non riprodotto: $e" }
        }
    }

    override fun close() {
        pending.close()
        scope.cancel()
        player.close()
    }

    companion object {
        const val TAG = "Palette"

        /**
         * 24 colpi di palette da 30 ms, ritagliati da "Split Flap Display" di ngruber
         * (freesound.org/s/261244, CC0).
         */
        const val CLICKS = "files/flap-clicks.wav"

        /** Posizione del cursore del volume durante un annuncio (se non è già più bassa). */
        const val DUCKED_VOLUME = 0.2f

        /** Attesa per raccogliere le girate partite insieme. */
        private const val BATCH_MILLIS = 30L

        private const val PREVIEW_FLIPS = 8
        private const val PREVIEW_STEP_MILLIS = 40
    }
}

/** Colpi di palette registrati, in campioni -1..1, tutti a [sampleRate]. */
class FlapClicks(val sampleRate: Int, val clicks: List<FloatArray>) {
    init {
        require(clicks.isNotEmpty()) { "Nessun colpo" }
    }

    companion object {
        /**
         * Legge una WAV PCM mono a 16 bit con i colpi uno dopo l'altro, ognuno in uno slot di
         * [slotMillis] ms (l'ultimo slot incompleto si scarta).
         */
        fun fromWav(bytes: ByteArray, slotMillis: Int = 30): FlapClicks {
            fun u16(at: Int) =
                (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            fun tag(at: Int) = bytes.decodeToString(at, at + 4)
            require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "Non è una WAV" }
            var sampleRate = 0
            var data: IntRange? = null
            var at = 12
            while (at + 8 <= bytes.size) {
                val size = u32(at + 4)
                val body = at + 8
                when (tag(at)) {
                    "fmt " -> {
                        require(u16(body) == 1 && u16(body + 2) == 1 && u16(body + 14) == 16) {
                            "Serve PCM mono a 16 bit"
                        }
                        sampleRate = u32(body + 4)
                    }

                    "data" -> data = body until minOf(body + size, bytes.size)
                }
                at = body + size + (size and 1)
            }
            require(sampleRate > 0) { "Manca il formato" }
            val range = requireNotNull(data) { "Mancano i campioni" }
            val samples = FloatArray((range.last - range.first + 1) / 2) { i ->
                (u16(range.first + 2 * i).toShort() / 32_768f)
            }
            val slot = sampleRate * slotMillis / 1000
            return FlapClicks(
                sampleRate,
                List(samples.size / slot) { k -> samples.copyOfRange(k * slot, (k + 1) * slot) },
            )
        }
    }
}

/** Crepitio a cui le palette segnalano le girate; null = palette silenziose. */
val LocalFlapClatter = staticCompositionLocalOf<FlapClatter?> { null }

/** Finestra in cui si contano i colpi simultanei. */
private const val BIN_MILLIS = 10

/** Colpi al più per finestra: oltre, il crepitio è già saturo e si risparmia calcolo. */
private const val MAX_PER_BIN = 30

/**
 * Traccia PCM mono a 16 bit con un colpo registrato per ogni girata, [eventsMillis] ms
 * dall'inizio, alla frequenza di [clicks].
 *
 * Ogni girata prende un colpo a caso, con volume e velocità un po' diversi (±6%, come palette e
 * motori che non sono mai identici). Più girate nella stessa finestra suonano più forte, ma
 * meno che in proporzione, e la somma passa da un soft-clip: anche un tabellone intero che gira
 * non satura.
 */
internal fun mixClatter(
    eventsMillis: IntArray,
    clicks: FlapClicks,
    random: Random = Random.Default,
): ShortArray {
    val bins = eventsMillis.filter { it >= 0 }.groupBy { it / BIN_MILLIS }
    if (bins.isEmpty()) return ShortArray(0)
    val rate = clicks.sampleRate
    val longest = clicks.clicks.maxOf { it.size }
    val last = bins.keys.max() * BIN_MILLIS + BIN_MILLIS
    // Coda per il colpo più lungo, anche rallentato.
    val mix = FloatArray((last.toLong() * rate / 1000).toInt() + longest * 11 / 10)

    for ((_, events) in bins) {
        val kept =
            if (events.size <= MAX_PER_BIN) events else events.shuffled(random).take(MAX_PER_BIN)
        val gain = 0.6f / kept.size.toFloat().pow(0.6f)
        for (ms in kept) {
            val click = clicks.clicks[random.nextInt(clicks.clicks.size)]
            val start = (ms.toLong() * rate / 1000).toInt()
            val amp = gain * (0.6f + 0.4f * random.nextFloat())
            val speed = 0.94f + 0.12f * random.nextFloat()
            val length = ((click.size - 1) / speed).toInt()
            for (i in 0 until length) {
                // Interpolazione lineare: il colpo rallentato o accelerato resta pulito.
                val pos = i * speed
                val j = pos.toInt()
                val frac = pos - j
                mix[start + i] += amp * (click[j] + (click[j + 1] - click[j]) * frac)
            }
        }
    }
    return ShortArray(mix.size) { i ->
        (tanh(mix[i] * 1.2f) * 0.9f * Short.MAX_VALUE).toInt().toShort()
    }
}
