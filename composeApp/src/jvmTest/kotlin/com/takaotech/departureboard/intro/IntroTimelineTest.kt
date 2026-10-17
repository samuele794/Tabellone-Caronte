package com.takaotech.departureboard.intro

import com.takaotech.departureboard.Departure
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.tts.mp3Duration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroTimelineTest {

    private val station = Station("1728", "Milano Centrale")

    private fun textAt(seconds: Double) =
        consoleAt((seconds * 1000).toLong(), station).map { it.text }

    @Test
    fun `l'intro dura quanto la sua musica`() {
        val bytes = File("src/commonMain/composeResources/${IntroTimeline.TRACK}").readBytes()
        // I frame contano anche il ritardo e il riempimento dell'encoder (LAME), ~60 ms in più
        // dell'audio vero (45,289 s per ffprobe).
        val duration = mp3Duration(bytes)!!
        assertEquals(IntroTimeline.TOTAL.toDouble(), duration.inWholeMilliseconds.toDouble(), 80.0)
    }

    @Test
    fun `le fasi seguono le battute dell'audio`() {
        assertEquals(IntroPhase.Terminal, IntroTimeline.phaseAt(0))
        assertEquals(IntroPhase.Terminal, IntroTimeline.phaseAt(37_499))
        assertEquals(IntroPhase.Glitch, IntroTimeline.phaseAt(37_500))
        assertEquals(IntroPhase.Dashboard, IntroTimeline.phaseAt(38_000))
        assertEquals(IntroPhase.Banner, IntroTimeline.phaseAt(41_250))
        assertEquals(IntroPhase.Zoom, IntroTimeline.phaseAt(44_000))
        assertEquals(IntroPhase.Done, IntroTimeline.phaseAt(IntroTimeline.TOTAL))
        assertEquals(IntroPhase.entries.sorted(), IntroPhase.entries)
    }

    @Test
    fun `all'inizio la console è vuota`() {
        assertEquals(emptyList(), textAt(0.0))
    }

    @Test
    fun `i comandi si battono un carattere alla volta`() {
        // "CHIAVE: " compare a 3,9 s; la chiave si batte a 18 caratteri al secondo.
        assertEquals("CHIAVE: ", textAt(3.9).last())
        assertEquals("CHIAVE: 0F51839-047A5-0F51", textAt(4.9).last())
        assertEquals("CHIAVE: 0F51839-047A5-0F51905-E1B5F0-0FS1839", textAt(6.4).last())
    }

    @Test
    fun `a 8 secondi si vede l'elenco dei comandi`() {
        val lines = textAt(8.0)
        assertTrue("[AUT] OK" in lines)
        assertTrue(lines.any { it.startsWith("CONNETTI | AUT") })
    }

    @Test
    fun `PULISCI svuota la console`() {
        assertEquals(listOf("\$//> STAZIONE"), textAt(9.3))
    }

    @Test
    fun `a 22 secondi la prima stazione non risponde, in rosso`() {
        val lines = consoleAt(22_000, station)
        val errors = lines.filter { it.kind == LineKind.Error }.map { it.text }
        assertEquals(
            listOf(
                "[STAZIONE] TEMPO DI CONNESSIONE SCADUTO",
                "[STAZIONE] LA STAZIONE NON RISPONDE"
            ),
            errors,
        )
    }

    @Test
    fun `il terminale si collega alla stazione del tabellone`() {
        assertTrue("[SIS] STAZIONE 1728 MILANO CENTRALE SELEZIONATA" in textAt(28.5))
        assertEquals("\$MILANO_CENTRALE//> ", textAt(28.5).last())
        assertEquals("[SIS] CONNESSIONE A MILANO CENTRALE", textAt(29.5).first())
    }

    @Test
    fun `senza cache la prima stazione provata è MI 81`() {
        assertEquals("\$//> STAZIONE SELEZIONA MI 81", textAt(14.9).dropLast(1).last())
        assertEquals("[SIS] COMPARTIMENTO MI STAZIONE 81 SELEZIONATA", textAt(14.9).last())
        assertEquals("\$STAZIONE_MI_81//> ^C", textAt(22.5).last())
        assertEquals("[ERR] COMPARTIMENTO \"B2\" INESISTENTE", textAt(26.0).last())
    }

    @Test
    fun `con la cache prova una stazione vera, nello stesso formato di quella finale`() {
        val decoy = Station("1888", "Napoli Centrale")
        fun text(seconds: Double) =
            consoleAt((seconds * 1000).toLong(), station, decoy).map { it.text }
        assertTrue(text(13.0).any { it.startsWith("SELEZIONA [CODICE] |") })
        assertEquals("\$//> STAZIONE SELEZIONA 1888", text(14.9).dropLast(1).last())
        assertEquals("[SIS] STAZIONE 1888 NAPOLI CENTRALE SELEZIONATA", text(14.9).last())
        assertEquals("\$NAPOLI_CENTRALE//> ", text(15.0).last())
        assertEquals("[SIS] CONNESSIONE A NAPOLI CENTRALE", text(16.0).first())
        assertEquals("\$NAPOLI_CENTRALE//> ^C", text(22.5).last())
        assertEquals("[ERR] STAZIONE \"B2\" INESISTENTE", text(26.0).last())
        assertTrue("[SIS] STAZIONE 1728 MILANO CENTRALE SELEZIONATA" in text(28.5))
    }

    @Test
    fun `con i codici lunghi la risposta aspetta l'ultimo carattere`() {
        val fnm = Station("S01066", "Milano Cadorna")
        val script = consoleScript(fnm, Station("S01416", "Abbadia Lariana"))
        for (command in script.filter { it.kind == LineKind.Command && it.text.startsWith("STAZIONE SELEZIONA S") }) {
            val typed = command.typeAt + (command.text.length * 1000f / TYPE_CPS).toLong()
            val reply = script.first { it.at > command.at && it.kind == LineKind.Output }
            assertTrue(
                reply.at > typed,
                "\"${reply.text}\" a ${reply.at} prima della fine di \"${command.text}\" ($typed)"
            )
        }
        // Gli eventi restano in ordine.
        assertEquals(script.sortedBy { it.at }, script)
    }

    @Test
    fun `alla fine l'handshake è completato`() {
        assertEquals(
            listOf("[RETE] HANDSHAKE COMPLETATO", "[STAZIONE] CONNESSA", "\$MILANO_CENTRALE//> "),
            textAt(37.4),
        )
    }

    @Test
    fun `la chiave privata scorre riga per riga`() {
        val early = textAt(31.0)
        val late = textAt(35.85)
        assertEquals(listOf("[RETE] -----INIZIO CHIAVE PRIVATA SCC-----"), early)
        assertEquals(27, late.size)
        assertTrue(
            late.drop(1)
                .all { it.matches(Regex("""\[RETE] ([A-Z0-9]{5}-){2}[A-Z0-9]{5}( ([A-Z0-9]{5}-){2}[A-Z0-9]{5}){2}""")) })
    }

    @Test
    fun `sigle e prompt della stazione`() {
        assertEquals("MILANO_CENTRALE", promptName(station))
        assertEquals("MC", stationInitials(station))
        assertEquals("BE", stationInitials(Station("1", "Bergamo")))
        assertEquals("ROMA_TERMINI", promptName(Station("2", "Roma Termini")))
        assertEquals("S_M_NOVELLA", promptName(Station("3", "S. M. Novella")))
    }

    @Test
    fun `prossima partenza con il ritardo, anche dopo mezzanotte`() {
        fun departure(time: String, delay: Int? = null) =
            Departure("R", time.replace(":", ""), "X", time, delay)

        // 23:50:00
        val info = IntroInfo(
            station,
            departures = listOf(departure("23:40", 15), departure("23:58"), departure("00:05")),
            startMillisOfDay = 23L * 3_600_000 + 50 * 60_000,
        )
        // 23:40 + 15' = 23:55: 5 minuti.
        assertEquals("2340" to 300L, info.nextDeparture(0)!!.let { it.first.number to it.second })
        // Dieci minuti dopo resta 00:05 (5 minuti); 23:55 e 23:58 sono partiti.
        assertEquals(
            "0005" to 300L,
            info.nextDeparture(600_000)!!.let { it.first.number to it.second })
        assertEquals("23:50:00", info.clockAt(0))
        assertEquals("00:00:01", info.clockAt(601_000))
        assertNull(IntroInfo(station).nextDeparture(0))
    }

    @Test
    fun `il banner si apre con lo stinger e si chiude prima dello zoom`() {
        assertEquals(0f, bannerAmount(41_000))
        assertEquals(1f, bannerAmount(42_000))
        assertEquals(0f, bannerAmount(IntroTimeline.BANNER_OUT_AT + IntroTimeline.BANNER_OUT))
    }

    @Test
    fun `il pulsante SKIP compare dopo un attimo`() {
        assertEquals(0f, skipButtonAlpha(0))
        assertEquals(0f, skipButtonAlpha(IntroTimeline.SKIP_ARMED_AT))
        assertEquals(1f, skipButtonAlpha(IntroTimeline.SKIP_ARMED_AT + 300))
        assertEquals(1f, skipButtonAlpha(37_000))
    }

    @Test
    fun `il cruscotto tiene il tabellone nelle proporzioni dello schermo`() {
        val g = dashboardGeometry(1920f, 1080f, minSide = 100f)
        assertTrue(g.showSides)
        assertEquals(1920f / 1080f, g.center.width / g.center.height, 0.001f)
        assertEquals(g.boardScale, g.center.height / 1080f, 0.001f)
        // Le colonne non toccano il riquadro centrale.
        assertTrue(g.margin + g.side <= g.center.left)
        assertEquals(1080f, g.margin * 4 + g.header + g.center.height + g.band, 0.01f)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1920f, 1080f), g.boardRect(1f))
        // Su un telefono in verticale (96 dp a densità 2,75) le colonne laterali non ci stanno.
        assertTrue(!dashboardGeometry(1080f, 2400f, minSide = 264f).showSides)
    }
}
