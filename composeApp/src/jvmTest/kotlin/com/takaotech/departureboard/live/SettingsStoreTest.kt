package com.takaotech.departureboard.live

import com.takaotech.departureboard.BackgroundSound
import com.takaotech.departureboard.BoardStyle
import com.takaotech.departureboard.FlapClatter
import com.takaotech.departureboard.announce.Announcement
import com.takaotech.departureboard.announce.Announcer
import com.takaotech.departureboard.announce.train
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.LoopPlayer
import com.takaotech.departureboard.tts.PcmPlayer
import com.takaotech.departureboard.tts.SoundPlayer
import com.takaotech.departureboard.tts.SpeechEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SettingsStoreTest {

    private val dir = Files.createTempDirectory("settings").toFile()
    private val path = File(dir, "nested/settings.json").absolutePath

    private val milano = Station("1728", "MILANO CENTRALE")
    private val cadorna = Station("S01066", "MILANO CADORNA")
    private val saronno = Station("S01062", "SARONNO")
    private val roma = Station("2416", "ROMA TERMINI")

    private class FakeSource(
        override val source: StationSource,
        override val defaultStation: Station,
        override var listRefresh: Duration? = null,
    ) : BoardSource {
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode) =
            RfiBoard(station.name, mode, emptyList(), emptyList())

        override fun close() = Unit
    }

    /** Fornitori appena creati, come alla riapertura dell'app. */
    private fun newSources() = listOf(
        FakeSource(StationSource.Rfi, milano),
        FakeSource(StationSource.Fnm, cadorna, listRefresh = 5.minutes),
    ).associateBy { it.source }

    private val sources = newSources()

    private val saronnoQuiet = BoardSettings(
        selection = Selection(StationSource.Fnm, saronno),
        autoScroll = false,
        announce = false,
    )

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Attende che la scrittura in background di [settings] sia arrivata su disco. */
    private suspend fun SettingsStore.awaitSaved(settings: BoardSettings) {
        withTimeout(5_000) { this@awaitSaved.settings.first { it == settings } }
    }

    private fun saveOnDisk(settings: BoardSettings) = runBlocking {
        SettingsStore(path).use { it.save(settings) }
    }

    @Test
    fun `senza file valgono le impostazioni predefinite`() = runBlocking {
        assertEquals(BoardSettings(), SettingsStore(path).use { it.load() })
    }

    @Test
    fun `le impostazioni sopravvivono alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            store.save(BoardSettings(Selection(StationSource.Rfi, milano)))
            store.save(saronnoQuiet)
            store.awaitSaved(saronnoQuiet)
        }

        assertEquals(saronnoQuiet, SettingsStore(path).use { it.load() })
    }

    @Test
    fun `chiudere subito dopo il salvataggio non perde le impostazioni`() = runBlocking {
        saveOnDisk(saronnoQuiet)

        assertEquals(saronnoQuiet, SettingsStore(path).use { it.load() })
    }

    @Test
    fun `i campi mancanti nel file prendono il valore predefinito`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText("""{"autoScroll":false}""")

        assertEquals(BoardSettings(autoScroll = false), SettingsStore(path).use { it.load() })
    }

    @Test
    fun `un file illeggibile vale come impostazioni predefinite`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }
            .writeText("""{"selection":{"source":"Sconosciuto"}}""")

        assertEquals(BoardSettings(), SettingsStore(path).use { it.load() })
    }

    @Test
    fun `il tabellone riparte dalle ultime impostazioni`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.select(StationSource.Fnm, saronno)
            state.autoScroll = false
            state.announce = false
            store.awaitSaved(saronnoQuiet)
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertFalse(state.restored)
            assertEquals(milano, state.station)
            assertTrue(state.autoScroll)
            assertTrue(state.announce)

            state.restoreSettings()

            assertTrue(state.restored)
            assertEquals(StationSource.Fnm, state.source)
            assertEquals(saronno, state.station)
            assertFalse(state.autoScroll)
            assertFalse(state.announce)
        }
    }

    @Test
    fun `le impostazioni di risparmio energetico sopravvivono alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.ecoMode = true
            state.screenOnOnlyWhenCharging = true
            state.oledPalette = true
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    ecoMode = true,
                    screenOnOnlyWhenCharging = true,
                    oledPalette = true,
                )
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertFalse(state.ecoMode)
            state.restoreSettings()
            assertTrue(state.ecoMode)
            assertTrue(state.screenOnOnlyWhenCharging)
            assertTrue(state.oledPalette)
        }
    }

    @Test
    fun `intro e cruscotto sopravvivono alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.intro = false
            state.dashboard = true
            store.awaitSaved(
                BoardSettings(Selection(StationSource.Rfi, milano), intro = false, dashboard = true)
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertFalse(state.dashboard)
            state.restoreSettings()
            assertFalse(state.intro)
            assertTrue(state.dashboard)
        }
    }

    @Test
    fun `aspetto e suono delle palette sopravvivono alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.boardStyle = BoardStyle.SplitFlap
            state.flapSound = false
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    boardStyle = BoardStyle.SplitFlap,
                    flapSound = false,
                )
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertEquals(BoardStyle.Led, state.boardStyle)
            state.restoreSettings()
            assertEquals(BoardStyle.SplitFlap, state.boardStyle)
            assertFalse(state.flapSound)
        }
    }

    @Test
    fun `la pausa tra le pagine sopravvive alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.flapPagePause = 10.seconds
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    flapPageSeconds = 10
                )
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertEquals(LiveBoardState.DEFAULT_FLAP_PAGE_PAUSE, state.flapPagePause)
            state.restoreSettings()
            assertEquals(10.seconds, state.flapPagePause)
        }
    }

    @Test
    fun `la durata del lampeggio sopravvive alla riapertura`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.announceBlink = 20.seconds
            store.awaitSaved(
                BoardSettings(Selection(StationSource.Rfi, milano), announceBlinkSeconds = 20)
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            assertEquals(LiveBoardState.DEFAULT_ANNOUNCE_BLINK, state.announceBlink)
            state.restoreSettings()
            assertEquals(20.seconds, state.announceBlink)
        }
    }

    @Test
    fun `una durata del lampeggio scritta a mano si aggancia all'opzione più vicina`() =
        runBlocking {
            File(path).apply { parentFile.mkdirs() }.writeText("""{"announceBlinkSeconds":45}""")

            SettingsStore(path).use { store ->
                val state = LiveBoardState(sources, settingsStore = store)
                state.restoreSettings()
                assertEquals(30.seconds, state.announceBlink)
            }
        }

    @Test
    fun `il volume delle palette sopravvive alla riapertura e arriva al crepitio`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.flapVolume = 40
            store.awaitSaved(BoardSettings(Selection(StationSource.Rfi, milano), flapVolume = 40))
        }

        SettingsStore(path).use { store ->
            FlapClatter(SilentPlayer, clicks = { ByteArray(0) }).use { clatter ->
                val state = LiveBoardState(sources, settingsStore = store, flapClatter = clatter)
                assertEquals(LiveBoardState.DEFAULT_FLAP_VOLUME, state.flapVolume)
                assertEquals(1f, clatter.volume)
                state.restoreSettings()
                assertEquals(40, state.flapVolume)
                assertEquals(0.4f, clatter.volume)
            }
        }
    }

    @Test
    fun `un volume scritto a mano si aggancia all'opzione più vicina`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText("""{"flapVolume":137}""")

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            assertEquals(100, state.flapVolume)
        }
    }

    @Test
    fun `il volume degli annunci sopravvive alla riapertura e arriva all'annunciatore`() =
        runBlocking {
            val silent = object : SpeechEngine {
                override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) = Unit
                override fun close() {}
            }
            val mute = object : SoundPlayer {
                override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) =
                    Unit

                override fun close() {}
            }
            SettingsStore(path).use { store ->
                Announcer(silent, mute, jingle = { ByteArray(0) }).use { announcer ->
                    val state =
                        LiveBoardState(sources, announcer = announcer, settingsStore = store)
                    state.restoreSettings()
                    state.announceVolume = 30
                    assertEquals(0.3f, announcer.volume)
                    store.awaitSaved(
                        BoardSettings(
                            Selection(StationSource.Rfi, milano),
                            announceVolume = 30
                        )
                    )
                }
            }

            SettingsStore(path).use { store ->
                Announcer(silent, mute, jingle = { ByteArray(0) }).use { announcer ->
                    val state =
                        LiveBoardState(sources, announcer = announcer, settingsStore = store)
                    assertEquals(LiveBoardState.DEFAULT_ANNOUNCE_VOLUME, state.announceVolume)
                    state.restoreSettings()
                    assertEquals(30, state.announceVolume)
                    assertEquals(0.3f, announcer.volume)
                }
            }
        }

    @Test
    fun `durante un annuncio le palette si abbassano`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val speech = object : SpeechEngine {
            override suspend fun speak(text: String, volume: Float, onStart: () -> Unit) =
                gate.await()
            override fun close() {}
        }
        val sound = object : SoundPlayer {
            override suspend fun play(bytes: ByteArray, volume: Float, onStart: () -> Unit) {}
            override fun close() {}
        }
        Announcer(speech, sound, jingle = { ByteArray(0) }).use { announcer ->
            FlapClatter(SilentPlayer, clicks = { ByteArray(0) }).use { clatter ->
                val state = LiveBoardState(sources, announcer = announcer, flapClatter = clatter)
                val duck = launch { state.duckSoundsDuringAnnouncements() }
                announcer.enqueue(
                    listOf(
                        Announcement.Imminent(
                            train(number = "84"),
                            BoardMode.Departures
                        )
                    )
                )
                withTimeout(5_000) { clatter.gain.first { it < 1f } }
                assertTrue(clatter.ducked)
                gate.complete(Unit)
                withTimeout(5_000) { clatter.gain.first { it == 1f } }
                duck.cancel()
            }
        }
    }

    @Test
    fun `sottofondo e volume sopravvivono alla riapertura e arrivano al sottofondo`() =
        runBlocking {
            SettingsStore(path).use { store ->
                val state = LiveBoardState(sources, settingsStore = store)
                state.restoreSettings()
                state.background = false
                state.backgroundVolume = 70
                store.awaitSaved(
                    BoardSettings(
                        Selection(StationSource.Rfi, milano),
                        background = false,
                        backgroundVolume = 70,
                    )
                )
            }

            SettingsStore(path).use { store ->
                BackgroundSound(SilentLoop).use { background ->
                    val state =
                        LiveBoardState(sources, settingsStore = store, backgroundSound = background)
                    assertTrue(state.background)
                    assertEquals(LiveBoardState.DEFAULT_BACKGROUND_VOLUME, state.backgroundVolume)
                    assertEquals(0.5f, background.volume)
                    state.restoreSettings()
                    assertFalse(state.background)
                    assertEquals(70, state.backgroundVolume)
                    assertEquals(0.7f, background.volume)
                }
            }
        }

    @Test
    fun `l'audio silenziato non si salva e non tocca le impostazioni dei suoni`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.muted = true
            assertTrue(state.announce)
            assertTrue(state.background)
            assertTrue(state.flapSound)
            state.autoScroll = false
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    autoScroll = false
                )
            )
        }

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            assertFalse(state.muted, "alla riapertura il tabellone torna a suonare")
            assertFalse(state.autoScroll)
        }
    }

    @Test
    fun `un file senza il sottofondo lo accende a metà volume`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText("""{"oledPalette":true}""")

        val loaded = SettingsStore(path).use { it.load() }
        assertTrue(loaded.background)
        assertEquals(50, loaded.backgroundVolume)
    }

    private object SilentLoop : LoopPlayer {
        override suspend fun loop(path: String, gain: StateFlow<Float>) = Unit
        override fun close() = Unit
    }

    private object SilentPlayer : PcmPlayer {
        override suspend fun play(samples: ShortArray, sampleRate: Int, gain: StateFlow<Float>) =
            Unit

        override fun close() = Unit
    }

    @Test
    fun `una pausa scritta a mano si aggancia all'opzione più vicina`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText("""{"flapPageSeconds":7}""")

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            assertEquals(6.seconds, state.flapPagePause)
        }
    }

    @Test
    fun `un file senza l'aspetto apre il tabellone a LED con il suono delle palette acceso`() =
        runBlocking {
            File(path).apply { parentFile.mkdirs() }.writeText("""{"oledPalette":true}""")

            val loaded = SettingsStore(path).use { it.load() }
            assertEquals(BoardStyle.Led, loaded.boardStyle)
            assertTrue(loaded.flapSound)
            assertEquals(100, loaded.flapVolume)
        }

    @Test
    fun `un cambio fatto prima del ripristino vince solo per quell'impostazione`() = runBlocking {
        saveOnDisk(saronnoQuiet)

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.select(StationSource.Rfi, roma)
            state.announce = true
            state.restoreSettings()

            assertEquals(StationSource.Rfi, state.source)
            assertEquals(roma, state.station)
            assertTrue(state.announce)
            // Non toccato prima del ripristino: torna quello salvato.
            assertFalse(state.autoScroll)

            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, roma),
                    autoScroll = false,
                    announce = true
                )
            )
        }
    }

    @Test
    fun `un fornitore non più disponibile viene ignorato`() = runBlocking {
        saveOnDisk(saronnoQuiet)

        SettingsStore(path).use { store ->
            val state = LiveBoardState(
                sources.filterKeys { it == StationSource.Rfi },
                settingsStore = store
            )
            state.restoreSettings()

            assertTrue(state.restored)
            assertEquals(StationSource.Rfi, state.source)
            assertEquals(milano, state.station)
            // Le altre impostazioni si ripristinano comunque.
            assertFalse(state.autoScroll)
            assertFalse(state.announce)
        }
    }

    @Test
    fun `gli intervalli di aggiornamento sopravvivono alla riapertura`() = runBlocking {
        val expected = BoardSettings(
            refreshSeconds = mapOf(StationSource.Rfi to 60, StationSource.Fnm to 45),
            listRefreshSeconds = mapOf(StationSource.Fnm to 600),
        )
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.setRefreshInterval(StationSource.Rfi, 1.minutes)
            state.setRefreshInterval(StationSource.Fnm, 45.seconds)
            state.setListRefreshInterval(StationSource.Fnm, 10.minutes)
            store.awaitSaved(expected.copy(selection = Selection(StationSource.Rfi, milano)))
        }

        val reopened = newSources()
        SettingsStore(path).use { store ->
            val state = LiveBoardState(reopened, settingsStore = store)
            assertEquals(LiveBoardState.DEFAULT_REFRESH, state.refreshInterval(StationSource.Rfi))
            assertEquals(5.minutes, state.listRefreshInterval(StationSource.Fnm))

            state.restoreSettings()

            assertEquals(1.minutes, state.refreshInterval(StationSource.Rfi))
            assertEquals(45.seconds, state.refreshInterval(StationSource.Fnm))
            assertEquals(10.minutes, state.listRefreshInterval(StationSource.Fnm))
            // L'intervallo dell'elenco arriva anche al fornitore, che è quello che lo usa.
            assertEquals(10.minutes, reopened.getValue(StationSource.Fnm).listRefresh)
        }
    }

    @Test
    fun `l'elenco treni si regola solo per i fornitori che lo riscaricano a parte`() {
        val state = LiveBoardState(sources)

        assertNull(state.listRefreshInterval(StationSource.Rfi))
        assertEquals(5.minutes, state.listRefreshInterval(StationSource.Fnm))
    }

    @Test
    fun `un intervallo fuori elenco si aggancia al più vicino`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText(
            """{"refreshSeconds":{"Rfi":50,"Fnm":-3},"listRefreshSeconds":{"Fnm":100000,"Rfi":60}}"""
        )

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()

            assertEquals(45.seconds, state.refreshInterval(StationSource.Rfi))
            assertEquals(15.seconds, state.refreshInterval(StationSource.Fnm))
            assertEquals(30.minutes, state.listRefreshInterval(StationSource.Fnm))
            assertNull(state.listRefreshInterval(StationSource.Rfi))
        }
    }

    @Test
    fun `un intervallo cambiato prima del ripristino vince solo per quel fornitore`() =
        runBlocking {
            saveOnDisk(
                BoardSettings(
                    refreshSeconds = mapOf(
                        StationSource.Rfi to 120,
                        StationSource.Fnm to 180
                    )
                )
            )

            SettingsStore(path).use { store ->
                val state = LiveBoardState(sources, settingsStore = store)
                state.setRefreshInterval(StationSource.Rfi, 15.seconds)
                state.restoreSettings()

                assertEquals(15.seconds, state.refreshInterval(StationSource.Rfi))
                assertEquals(3.minutes, state.refreshInterval(StationSource.Fnm))
            }
        }

    @Test
    fun `etichette degli intervalli`() {
        assertEquals("45 s", intervalLabel(45.seconds))
        assertEquals("2 min", intervalLabel(2.minutes))
        assertEquals("1 min 30 s", intervalLabel(90.seconds))
    }

    @Test
    fun `i dettagli non vanno oltre l'elenco treni`() = runBlocking {
        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()
            state.setListRefreshInterval(StationSource.Fnm, 1.minutes)

            assertEquals(1.minutes, state.maxRefreshInterval(StationSource.Fnm))
            state.setRefreshInterval(StationSource.Fnm, 90.seconds)

            // Si ferma al limite e l'elenco resta quello scelto.
            assertEquals(1.minutes, state.refreshInterval(StationSource.Fnm))
            assertEquals(1.minutes, state.listRefreshInterval(StationSource.Fnm))
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    refreshSeconds = mapOf(StationSource.Fnm to 60),
                    listRefreshSeconds = mapOf(StationSource.Fnm to 60),
                )
            )
        }
    }

    @Test
    fun `l'elenco treni non scende sotto i dettagli`() {
        val state = LiveBoardState(sources)
        state.setRefreshInterval(StationSource.Fnm, 90.seconds)

        assertEquals(2.minutes, state.minListRefreshInterval(StationSource.Fnm))
        state.setListRefreshInterval(StationSource.Fnm, 1.minutes)

        assertEquals(2.minutes, state.listRefreshInterval(StationSource.Fnm))
        assertEquals(90.seconds, state.refreshInterval(StationSource.Fnm))
    }

    @Test
    fun `senza elenco treni i dettagli non hanno limiti`() {
        val state = LiveBoardState(sources)

        assertNull(state.maxRefreshInterval(StationSource.Rfi))
        assertNull(state.minListRefreshInterval(StationSource.Rfi))
        state.setRefreshInterval(StationSource.Rfi, 5.minutes)
        assertEquals(5.minutes, state.refreshInterval(StationSource.Rfi))
    }

    @Test
    fun `un file con i dettagli più radi dell'elenco viene corretto`() = runBlocking {
        saveOnDisk(
            BoardSettings(
                refreshSeconds = mapOf(StationSource.Fnm to 180),
                listRefreshSeconds = mapOf(StationSource.Fnm to 60),
            )
        )

        SettingsStore(path).use { store ->
            val state = LiveBoardState(sources, settingsStore = store)
            state.restoreSettings()

            assertEquals(1.minutes, state.refreshInterval(StationSource.Fnm))
            assertEquals(1.minutes, state.listRefreshInterval(StationSource.Fnm))
            store.awaitSaved(
                BoardSettings(
                    Selection(StationSource.Rfi, milano),
                    refreshSeconds = mapOf(StationSource.Fnm to 60),
                    listRefreshSeconds = mapOf(StationSource.Fnm to 60),
                )
            )
        }
    }

    @Test
    fun `lo slider si ferma ai limiti`() {
        val details = LiveBoardState.REFRESH_OPTIONS
        val list = LiveBoardState.LIST_REFRESH_OPTIONS

        assertEquals(details.indices, allowedIndices(details, null, null))
        // Dettagli con l'elenco a 2 minuti: da 15 s a 2 min.
        assertEquals(0..details.indexOf(2.minutes), allowedIndices(details, null, 2.minutes))
        // Elenco con i dettagli a 90 s: da 2 min a 30 min.
        assertEquals(list.indexOf(2.minutes)..list.lastIndex, allowedIndices(list, 2.minutes, null))
    }
}
