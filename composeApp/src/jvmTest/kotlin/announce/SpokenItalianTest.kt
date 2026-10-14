package announce

import announce.SpokenItalian.place
import announce.SpokenItalian.platform
import announce.SpokenItalian.time
import announce.SpokenItalian.trainNumber
import rfi.BoardMode
import rfi.Fixtures
import rfi.RfiParser
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenItalianTest {

    @Test
    fun `numeri in lettere`() {
        assertEquals("zero", SpokenItalian.number(0))
        assertEquals("diciassette", SpokenItalian.number(17))
        assertEquals("ventuno", SpokenItalian.number(21))
        assertEquals("ventitré", SpokenItalian.number(23))
        assertEquals("ventotto", SpokenItalian.number(28))
        assertEquals("quaranta", SpokenItalian.number(40))
        assertEquals("novantanove", SpokenItalian.number(99))
    }

    @Test
    fun `numero del treno secondo il MAS`() {
        assertEquals("ottantaquattro", trainNumber("84"))
        assertEquals("sette cinque tre", trainNumber("753"))
        assertEquals("novantaquattro quarantotto", trainNumber("9448"))
        assertEquals("dodici uno cinque sei", trainNumber("12156"))
        assertEquals("novantaquattro zero cinque", trainNumber("9405"))
        assertEquals("dodici quarantasette A", trainNumber("1247A"), "suffisso dei bus RFI")
    }

    @Test
    fun orari() {
        assertEquals("sette e quaranta", time("07:40"))
        assertEquals("diciotto e zero cinque", time("18:05"))
        assertEquals("nove", time("09:00"))
        assertEquals("una e venti", time("01:20"))
        assertEquals("zero e cinquantadue", time("00:52"))
        assertEquals("--", time("--"), "orario non leggibile: lasciato com'è")
    }

    @Test
    fun binari() {
        assertEquals("tre", platform("3"))
        assertEquals("ventiquattro", platform("24"))
        assertEquals("dodici T R", platform("12 TR"))
        assertEquals("P E", platform("PE"))
        assertEquals("uno A", platform("1A"))
    }

    @Test
    fun `nomi di località`() {
        assertEquals("Bari Centrale", place("BARI C.LE"))
        assertEquals("Torino Porta Nuova", place("TORINO P.NUOVA"))
        assertEquals("Torino Porta Nuova", place("TORINO PORTA NUOVA"))
        assertEquals("San Giovanni", place("S.GIOVANNI"))
        assertEquals("Santa Maria", place("S.MARIA"))
        assertEquals("San Nicola", place("S.NICOLA"))
        assertEquals("Sant'Anna", place("S.ANNA"))
        assertEquals("Santo Stefano", place("S.STEFANO"))
        assertEquals("Cassano D'Adda", place("CASSANO D'ADDA"))
        assertEquals("Trinitapoli-San Ferdinando", place("TRINITAPOLI-S.FERDINANDO"))
        assertEquals("Firenze Santa Maria Novella", place("FIRENZE S.M.N."))
    }

    @Test
    fun `categorie e imprese`() {
        assertEquals("Alta Velocità", SpokenItalian.category("ALTA VELOCITA'"))
        assertEquals("Regionale", SpokenItalian.category("REG"))
        assertEquals("Eurostar", SpokenItalian.category("ES*"))
        assertEquals("Frecciarossa", SpokenItalian.category("FRECCIAROSSA"))
        assertEquals("Trenitalia", SpokenItalian.carrier("TRENITALIA"))
        assertEquals("Italo", SpokenItalian.carrier("ITALO"))
        assertEquals("TPER", SpokenItalian.carrier("TPER"))
    }

    @Test
    fun `fermate dalla colonna informazioni`() {
        val bergamo =
            RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures).trains.first()
        assertEquals(
            listOf(
                "Milano Lambrate",
                "Pioltello Limito",
                "Treviglio Ovest",
                "Verdello Dalmine",
                "Bergamo"
            ),
            SpokenItalian.stops(bergamo.info),
        )
        assertEquals(emptyList(), SpokenItalian.stops(""))
    }
}
