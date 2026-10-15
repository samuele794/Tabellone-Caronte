package com.takaotech.departureboard.rfi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Blocco "Informazioni" e treni cancellati, dalle pagine salvate il 14/10/2026. */
class RfiNotesTest {

    private val firenze =
        RfiParser.parseBoard(Fixtures.firenzePartenze, BoardMode.Departures).trains
    private val roma = RfiParser.parseBoard(Fixtures.romaPartenze, BoardMode.Departures).trains
    private val garibaldi =
        RfiParser.parseBoard(Fixtures.garibaldiPartenze, BoardMode.Departures).trains

    private fun List<RfiTrain>.number(n: String) = single { it.number == n }

    @Test
    fun `note separate dalle fermate`() {
        val italo = firenze.number("8929")
        assertEquals(listOf("CARROZZA 1 IN CODA AL TRENO"), italo.notes)
        assertTrue(italo.info.startsWith("FERMA A:ROMA TIBURTINA (15:57)"), italo.info)
        assertFalse("CARROZZA" in italo.info, "le note non finiscono tra le fermate")
    }

    @Test
    fun `note multiple, senza doppioni`() {
        assertEquals(listOf("VIA VILLAPIZZONE", "MILANO LAMBRATE"), garibaldi.number("2271").notes)
        assertEquals(listOf("NO-STOP"), roma.number("4634").notes)
        assertEquals(emptyList(), garibaldi.number("24249").notes)
    }

    @Test
    fun `treno cancellato dalla cella del ritardo`() {
        val cancelled = firenze.number("18377")
        assertTrue(cancelled.cancelled)
        assertEquals(null, cancelled.delayMinutes)
        assertFalse(firenze.number("8929").cancelled)
    }

    @Test
    fun `sul tabellone le note seguono le fermate`() {
        val info = RfiMapper().departure(firenze.number("8929")).info
        assertTrue(info.endsWith("NAPOLI CENTRALE (17:33) - CARROZZA 1 IN CODA AL TRENO"), info)
        assertEquals(
            "NO-STOP", RfiMapper().departure(
                RfiTrain(
                    carrier = "",
                    carrierLogoBase64 = null,
                    category = "",
                    number = "1",
                    station = "",
                    time = "",
                    delayMinutes = null,
                    platform = "",
                    flashing = false,
                    info = "",
                    notes = listOf("NO-STOP"),
                )
            ).info, "solo note: nessun trattino davanti"
        )
    }
}
