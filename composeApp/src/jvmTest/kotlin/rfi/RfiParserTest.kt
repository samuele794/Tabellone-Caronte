package rfi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RfiParserTest {

    @Test
    fun `partenze - scarta le righe di riempimento`() {
        val board = RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures)

        assertEquals("MILANO CENTRALE", board.stationName)
        assertEquals(listOf("1247A", "1911A", "2349A"), board.trains.map { it.number })
    }

    @Test
    fun `partenze - legge tutte le colonne`() {
        val bus = RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures).trains.first()

        assertEquals("TRENORD", bus.carrier)
        assertEquals("BUS", bus.category)
        assertEquals("BERGAMO", bus.station)
        assertEquals("00:05", bus.time)
        assertNull(bus.delayMinutes)
        assertEquals("PE", bus.platform)
        assertFalse(bus.flashing)
        assertTrue(bus.info.startsWith("FERMA A:MILANO LAMBRATE (0:19)"))
        assertTrue(assertNotNull(bus.carrierLogoBase64).startsWith("R0lGOD"), "logo GIF in base64")
    }

    @Test
    fun `partenze - apostrofi multipli nelle fermate`() {
        val train = RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures).trains[1]

        assertTrue("CASSANO D'ADDA (1:20)" in train.info, train.info)
    }

    @Test
    fun `arrivi - ritardo, binario e spia`() {
        val board = RfiParser.parseBoard(Fixtures.napoliArrivi, BoardMode.Arrivals)
        assertEquals("NAPOLI CENTRALE", board.stationName)
        assertEquals(BoardMode.Arrivals, board.mode)
        assertEquals(4, board.trains.size)

        val italo = board.trains[0]
        assertEquals("9955", italo.number)
        assertEquals("ITALO", italo.carrier)
        assertEquals("ALTA VELOCITA'", italo.category)
        assertEquals("TORINO PORTA NUOVA", italo.station)
        assertEquals("23:49", italo.time)
        assertEquals(10, italo.delayMinutes)
        assertEquals("24", italo.platform)
        assertTrue(italo.flashing)

        val regionale = board.trains[1]
        assertEquals("REG", regionale.category)
        assertEquals(10, regionale.delayMinutes)
        assertEquals("", regionale.platform)
        assertFalse(regionale.flashing)

        assertNull(board.trains[2].delayMinutes)
        assertEquals("ES*", board.trains[3].category)
    }

    @Test
    fun `avvisi della barra gialla`() {
        val html = """
            <html><body>
            <h1 class="nomestazione" id="nomeStazioneId"> TEST </h1>
            <table><tbody id="bodyTabId"></tbody></table>
            <div class="marqueeinfosupp"><div>
                SCIOPERO DEL PERSONALE
            </div></div>
            </body></html>
        """.trimIndent()

        val board = RfiParser.parseBoard(html, BoardMode.Departures)

        assertEquals("TEST", board.stationName)
        assertEquals(emptyList(), board.trains)
        assertEquals(listOf("SCIOPERO DEL PERSONALE"), board.notices)
    }

    @Test
    fun `elenco stazioni`() {
        val stations = RfiParser.parseStations(Fixtures.stazioni)

        assertEquals(2435, stations.size)
        assertEquals(Station(364, "ABANO TERME"), stations.first())
        assertTrue(Station(1728, "MILANO CENTRALE") in stations)
        assertTrue(Station(413, "ALI' TERME") in stations, "entità HTML decodificate")
    }
}
