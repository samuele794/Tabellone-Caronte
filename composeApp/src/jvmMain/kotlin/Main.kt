import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import announce.Announcement
import announce.AnnouncementText
import announce.Announcer
import departureboard.BoardLabels
import departureboard.BoardLayout
import departureboard.DepartureBoard
import departureboard.LocalLedTicker
import departureboard.SampleDepartures
import kotlinx.coroutines.runBlocking
import live.LiveBoard
import live.LiveBoardState
import live.rememberNow
import org.jetbrains.skia.EncodedImageFormat
import rfi.BoardMode
import rfi.RfiClient
import rfi.RfiTrain
import tts.DesktopSpeechEngine
import tts.JvmSoundPlayer
import java.io.File

//https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=1728&arrivals=False

fun main(args: Array<String>) {
    // ./gradlew run --args="--snapshot board.png [secondi] [de]"
    if (args.firstOrNull() == "--snapshot") {
        val out = File(args.getOrElse(1) { "board.png" })
        val seconds = args.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val german = args.getOrNull(3) == "de"
        snapshot(out, seconds, german)
        return
    }
    // ./gradlew run --args="--bench [frame]"
    if (args.firstOrNull() == "--bench") {
        bench(args.getOrNull(1)?.toIntOrNull() ?: 600)
        return
    }
    // ./gradlew run --args="--announce"
    if (args.firstOrNull() == "--announce") {
        announceDemo()
        return
    }
    val client = RfiClient()
    val announcer = Announcer(DesktopSpeechEngine(), JvmSoundPlayer())
    System.setProperty("skiko.fps.enabled", "true")
    application {
        val state = remember { LiveBoardState(client, announcer = announcer) }
        Window(
            onCloseRequest = {
                announcer.close()
                client.close()
                exitApplication()
            },
            title = "${if (state.mode == BoardMode.Arrivals) "Arrivi" else "Partenze"} — ${state.station.name}",
            state = rememberWindowState(width = 1400.dp, height = 1000.dp),
        ) {
            MaterialTheme {
                LiveBoard(state, now = rememberNow())
            }
        }
    }
}

/** Tabellone con i dati di esempio, per gli snapshot deterministici. */
@Composable
private fun Demo(german: Boolean = false) {
    val footer = SampleDepartures.footer

    Box(
        Modifier.fillMaxSize().background(Color(0xFFD9D9D6)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        DepartureBoard(
            departures = SampleDepartures.rows,
            footer = footer,
            labels = if (german) BoardLabels.ItalianGerman else BoardLabels(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun snapshot(out: File, seconds: Double, german: Boolean) {
    // Marquee e spie seguono il tempo della scena, non l'orologio reale.
    val ticker = mutableLongStateOf(0L)
    val scene = ImageComposeScene(width = 1600, height = 900, density = Density(1f)) {
        CompositionLocalProvider(LocalLedTicker provides ticker) { Demo(german = german) }
    }
    // Le animazioni infinite partono al primo frame: avanzo a passi di ~16 ms.
    val target = (seconds * 1_000_000_000).toLong()
    var t = 0L
    while (t < target) {
        ticker.longValue = tickerMillis(t)
        scene.render(t)
        t += 16_000_000
    }
    ticker.longValue = tickerMillis(target)
    val image = scene.render(target)
    out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("Saved ${out.absolutePath}")
}

/** Tempo di render per frame del tabellone a tutto schermo, con scorrimento verticale attivo. */
private fun bench(frames: Int) {
    val ticker = mutableLongStateOf(0L)
    val scene = ImageComposeScene(width = 1920 * 2, height = 1080 * 2, density = Density(2f)) {
        CompositionLocalProvider(LocalLedTicker provides ticker) {
            Box(Modifier.fillMaxSize().background(Color(0xFFD9D9D6)).padding(24.dp)) {
                DepartureBoard(
                    departures = SampleDepartures.rows + SampleDepartures.rows + SampleDepartures.rows,
                    footer = SampleDepartures.footer,
                    layout = BoardLayout(rows = null, scrollPauseMillis = 0),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val warmup = 60
    val times = LongArray(frames)
    var t = 0L
    repeat(warmup + frames) { i ->
        ticker.longValue = tickerMillis(t)
        val start = System.nanoTime()
        scene.render(t).close()
        if (i >= warmup) times[i - warmup] = System.nanoTime() - start
        t += 16_000_000
    }
    scene.close()
    times.sort()
    val avg = times.average() / 1e6
    val p95 = times[(frames * 0.95).toInt().coerceAtMost(frames - 1)] / 1e6
    println("Bench: %d frame, media %.2f ms, p95 %.2f ms".format(frames, avg, p95))
}

/** Jingle + annuncio P1 di esempio, per provare la voce del sistema operativo. */
private fun announceDemo() = runBlocking {
    val train = RfiTrain(
        carrier = "TRENITALIA",
        carrierLogoBase64 = null,
        category = "ALTA VELOCITA'",
        number = "9915",
        station = "BARI C.LE",
        time = "07:40",
        delayMinutes = 20,
        platform = "11",
        flashing = true,
        info = "FERMA A:BOLOGNA C.LE (9:30) - ANCONA (11:20) - PESCARA C.LE (12:40) - BARI C.LE (15:00)",
    )
    val announcement = Announcement.Imminent(train, BoardMode.Departures)
    println(announcement.code + ": " + AnnouncementText.text(announcement))
    Announcer(DesktopSpeechEngine(), JvmSoundPlayer()).use { it.announce(announcement) }
}

/** Tempo della scena a scatti di 100 ms, come [departureboard.rememberLedTicker]. */
private fun tickerMillis(nanos: Long) = nanos / 100_000_000 * 100
