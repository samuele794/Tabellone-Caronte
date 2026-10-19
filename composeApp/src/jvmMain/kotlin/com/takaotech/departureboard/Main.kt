package com.takaotech.departureboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.rememberWindowState
import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.AnnouncementText
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.cache.StationCache
import com.takaotech.departureboard.fnm.FnmBoardSource
import com.takaotech.departureboard.fnm.FnmClient
import com.takaotech.departureboard.fnm.FnmKeyStore
import com.takaotech.departureboard.intro.IntroHost
import com.takaotech.departureboard.intro.IntroInfo
import com.takaotech.departureboard.intro.IntroOverlay
import com.takaotech.departureboard.intro.introBoardLayer
import com.takaotech.departureboard.live.LiveBoard
import com.takaotech.departureboard.live.LiveBoardState
import com.takaotech.departureboard.live.SettingsStore
import com.takaotech.departureboard.live.rememberNow
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoardSource
import com.takaotech.departureboard.rfi.RfiClient
import com.takaotech.departureboard.rfi.RfiTrain
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.DesktopSpeechEngine
import com.takaotech.departureboard.tts.JvmLoopPlayer
import com.takaotech.departureboard.tts.JvmPcmPlayer
import com.takaotech.departureboard.tts.JvmSoundPlayer
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

//https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=1728&arrivals=False

fun main(args: Array<String>) {
    // ./gradlew run --args="--snapshot board.png [secondi] [de] [flap]"
    if (args.firstOrNull() == "--snapshot") {
        val out = File(args.getOrElse(1) { "board.png" })
        val seconds = args.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val german = "de" in args.drop(3)
        snapshot(out, seconds, german, style(args))
        return
    }
    // ./gradlew run --args="--intro-snapshot intro.png [secondi] [cruscotto]"
    if (args.firstOrNull() == "--intro-snapshot") {
        introSnapshot(
            File(args.getOrElse(1) { "intro.png" }),
            args.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            keepDashboard = "cruscotto" in args.drop(3),
        )
        return
    }
    // ./gradlew run --args="--bench [frame] [flap]"
    if (args.firstOrNull() == "--bench") {
        bench(args.getOrNull(1)?.toIntOrNull() ?: 600, style(args))
        return
    }
    // ./gradlew run --args="--announce"
    if (args.firstOrNull() == "--announce") {
        announceDemo()
        return
    }
    // ./gradlew run --args="--clatter [palette]"
    if (args.firstOrNull() == "--clatter") {
        clatterDemo(args.getOrNull(1)?.toIntOrNull() ?: 300)
        return
    }
    val dataDir = File(System.getProperty("user.home"), ".departure-board")
    val stationCache = StationCache(File(dataDir, "stations.json").absolutePath)
    val settingsStore = SettingsStore(File(dataDir, "settings.json").absolutePath)
    val fnmClient = FnmClient()
    val sources = listOf(
        RfiBoardSource(RfiClient()),
        FnmBoardSource(
            fnmClient,
            FnmKeyStore(File(dataDir, "fnm-key.json").absolutePath, fnmClient::webComponentsJs),
        ),
    ).associateBy { it.source }
    val announcer = Announcer(DesktopSpeechEngine(), JvmSoundPlayer())
    val flapClatter = FlapClatter(JvmPcmPlayer())
    val backgroundSound = BackgroundSound(JvmLoopPlayer())
    val introPlayer = JvmSoundPlayer()
    // Anche con `java -jar`, dove i jvmArgs di Gradle non arrivano.
    System.setProperty("skiko.fps.enabled", "true")
    System.setProperty("skiko.fps.count", "200")
    nucleusApplication(args, backend = NucleusBackend.Tao) {
        val state =
            remember {
                LiveBoardState(
                    sources,
                    announcer = announcer,
                    stationCache = stationCache,
                    settingsStore = settingsStore,
                    flapClatter = flapClatter,
                    backgroundSound = backgroundSound,
                )
            }
        val title =
            "${if (state.mode == BoardMode.Arrivals) "Arrivi" else "Partenze"} — ${state.station.name}"
        DecoratedWindow(
            onCloseRequest = {
                announcer.close()
                flapClatter.close()
                backgroundSound.close()
                stationCache.close()
                settingsStore.close()
                sources.values.forEach { it.close() }
                exitApplication()
            },
            title = title,
            state = rememberWindowState(width = 1400.dp, height = 1000.dp),
        ) {
            // Tao non disegna la cornice di sistema: la barra si trascina, col doppio clic
            // massimizza e porta i pulsanti della finestra (semafori su macOS).
            WindowScaffold(
                titleBar = {
                    TitleBar { _ ->
                        Text(
                            title,
                            color = LocalTitleBarStyle.current.colors.content,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                },
            ) {
                AppTheme {
                    IntroHost(state, introPlayer, onFinished = {}) {
                        LiveBoard(state, now = rememberNow())
                    }
                }
            }
        }
    }
}

/** "flap" tra gli argomenti di `--snapshot` e `--bench` = tabellone a palette. */
private fun style(args: Array<String>) =
    if ("flap" in args.drop(2)) BoardStyle.SplitFlap else BoardStyle.Led

/** Snapshot a palette: il tabellone parte vuoto e si riempie girando a questo istante della scena. */
private const val FLAP_FILL_MILLIS = 500L

/**
 * Tabellone con i dati di esempio, per gli snapshot deterministici. A palette parte vuoto e si
 * riempie dopo [FLAP_FILL_MILLIS], così si vedono le palette girare.
 */
@Composable
private fun Demo(german: Boolean = false, style: BoardStyle = BoardStyle.Led) {
    val footer = SampleDepartures.footer
    val ticker = LocalLedTicker.current
    val filled = style == BoardStyle.Led || (ticker?.value ?: 0L) >= FLAP_FILL_MILLIS

    Box(
        Modifier.fillMaxSize().background(Color(0xFFD9D9D6)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        DepartureBoard(
            departures = if (filled) SampleDepartures.rows else emptyList(),
            footer = footer,
            labels = if (german) BoardLabels.ItalianGerman else BoardLabels(),
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun snapshot(out: File, seconds: Double, german: Boolean, style: BoardStyle) {
    // Marquee e spie seguono il tempo della scena, non l'orologio reale.
    val ticker = mutableLongStateOf(0L)
    val scene = ImageComposeScene(width = 1600, height = 900, density = Density(1f)) {
        CompositionLocalProvider(LocalLedTicker provides ticker) {
            Demo(
                german = german,
                style = style
            )
        }
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

/**
 * Fotogramma dell'intro a [seconds] secondi, con il tabellone dei dati di esempio al posto di
 * quello live: serve a confrontarla con il video da cui è ispirata. Con [keepDashboard] il
 * tabellone resta nel cruscotto, come con l'impostazione.
 */
private fun introSnapshot(out: File, seconds: Double, keepDashboard: Boolean) {
    val millis = (seconds * 1000).toLong()
    val ticker = mutableLongStateOf(0L)
    val info = IntroInfo(
        station = Station("1728", "Milano Centrale"),
        departures = SampleDepartures.rows,
        notices = SampleDepartures.footer.messages,
        config = listOf(
            "ANNUNCI_VOCALI" to "TRUE",
            "SOTTOFONDO" to "TRUE",
            "STILE_TABELLONE" to "LED",
            "SCORRIMENTO_AUTO" to "TRUE",
            "MODALITA_RISPARMIO" to "FALSE",
            "TAVOLOZZA_OLED" to "FALSE",
            "AGGIORNAMENTO" to "30S",
            "FORNITORE" to "RFI",
            "STAZIONE_ID" to "1728",
        ),
        startMillisOfDay = (7L * 60 + 3) * 60_000,
    )
    val scene = ImageComposeScene(width = 1920, height = 1080, density = Density(1.2f)) {
        CompositionLocalProvider(LocalLedTicker provides ticker) {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize()
                            .introBoardLayer({ millis }, { 0f }, { true }, { keepDashboard })
                            .background(Color(0xFF1C1E24)),
                    ) {
                        DepartureBoard(
                            departures = SampleDepartures.rows,
                            footer = SampleDepartures.footer,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    IntroOverlay(
                        millis = { millis },
                        info = info,
                        keepDashboard = { keepDashboard },
                    )
                }
            }
        }
    }
    // Font e immagini si caricano nei primi frame.
    var t = 0L
    repeat(30) {
        ticker.longValue = tickerMillis(t)
        scene.render(t)
        t += 16_000_000
    }
    val image = scene.render(t)
    out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("Saved ${out.absolutePath}")
}

/**
 * Tempo di render per frame del tabellone a tutto schermo, con scorrimento verticale attivo. A
 * palette le partenze cambiano ogni 3 s, così si misurano anche le girate.
 */
private fun bench(frames: Int, style: BoardStyle) {
    val ticker = mutableLongStateOf(0L)
    val rows = SampleDepartures.rows + SampleDepartures.rows + SampleDepartures.rows
    val swapped = rows.reversed()
    val scene = ImageComposeScene(width = 1920 * 2, height = 1080 * 2, density = Density(2f)) {
        CompositionLocalProvider(LocalLedTicker provides ticker) {
            Box(Modifier.fillMaxSize().background(Color(0xFFD9D9D6)).padding(24.dp)) {
                val swap = style == BoardStyle.SplitFlap && ticker.longValue / 3_000 % 2 == 1L
                DepartureBoard(
                    departures = if (swap) swapped else rows,
                    footer = SampleDepartures.footer,
                    layout = BoardLayout(rows = null, scrollPauseMillis = 0),
                    style = style,
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

/**
 * Crepitio di [flaps] palette che girano insieme, ognuna di un numero casuale di giri, come quando
 * cambia una riga intera ([flaps] = 60) o tutto il tabellone (1000 e oltre).
 */
private fun clatterDemo(flaps: Int) = runBlocking {
    val random = kotlin.random.Random(1)
    val events = buildList {
        repeat(flaps) {
            val start = random.nextInt(250)
            repeat(1 + random.nextInt(30)) { k -> add(start + (k + 1) * 55) }
        }
    }.toIntArray()
    val clicks = FlapClicks.fromWav(Res.readBytes(FlapClatter.CLICKS))
    JvmPcmPlayer().use { it.play(mixClatter(events, clicks), clicks.sampleRate) }
}

/** Tempo della scena a scatti di 100 ms, come [rememberLedTicker]. */
private fun tickerMillis(nanos: Long) = nanos / 100_000_000 * 100
