package announce

import rfi.BoardMode
import rfi.Fixtures
import rfi.RfiParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceNamesTest {

    private val places = PlaceNames(RfiParser.parseStations(Fixtures.stazioni).map { it.name })

    @Test
    fun `abbreviazioni sciolte con l'elenco delle stazioni`() {
        assertEquals("CASTIGLION FIORENTINO", places.resolve("CASTIGL.FIORENT."))
        assertEquals("LA SPEZIA CENTRALE", places.resolve("LA SPEZIA C."))
        assertEquals("MILANO PORTA GARIBALDI", places.resolve("MI.P.GARIBALDI"))
        assertEquals("OULX CESANA CLAVIERE SESTRIERE", places.resolve("OULX CE.CL.SES."))
        assertEquals("GENOVA SESTRI PONENTE AEROPORTO", places.resolve("GE SESTRI P. AER"))
        assertEquals("S.GIOVANNI IN PERSICETO", places.resolve("S.G.PERSICETO"))
        assertEquals("DUGENTA - FRASSO TELESINO", places.resolve("DUGENTA FRASSO T"))
        assertEquals("ISOLA DELLE FEMMINE", places.resolve("ISOLA D.FEMMINE"))
        assertEquals("BARI CENTRALE", places.resolve("BARI C.LE"), "C.LE vale CENTRALE")
    }

    @Test
    fun `nomi interi e S non si confondono`() {
        assertEquals("MILANO CENTRALE", places.resolve("MILANO CENTRALE"))
        assertTrue(places.resolve("S. MARCO") != "SASSO MARCONI")
        assertTrue(places.resolve("S.ROMANO") != "SEZZE ROMANO")
        assertNull(places.resolve("BARI"), "un nome intero non si allunga in BARI CENTRALE")
        assertNull(places.resolve("MARINO"), "la S di S.MARINO non si salta")
        assertNull(places.resolve("COLLEFERRO"), "non diventa COLLEFERRO-SEGNI-PALIANO")
        assertEquals("SESSA AURUNCA-ROCCAMONFINA", places.resolve("SESSA A."))
        assertEquals("CORBETTA-S.STEFANO TICINO", places.resolve("CORBETTA S.STEF."))
        assertEquals(
            "BORGO S.LORENZO",
            places.resolve("BORGO S.L."),
            "a parità vince il nome più corto"
        )
    }

    @Test
    fun `nome pronto per la voce`() {
        assertEquals("Castiglion Fiorentino", places.spoken("CASTIGL.FIORENT."))
        assertEquals("Borgo San Lorenzo", places.spoken("BORGO S.L."))
        assertEquals("Venezia Mestre", places.spoken("VE MESTRE"))
        assertEquals("Mercato San Severino", places.spoken("MERCATO S.SEVER."))
        assertEquals(
            "Bari Centrale",
            PlaceNames.None.spoken("BARI C.LE"),
            "senza elenco: regole fisse"
        )
    }

    @Test
    fun `fermate delle pagine reali senza abbreviazioni rimaste`() {
        val trains =
            listOf(Fixtures.firenzePartenze, Fixtures.garibaldiPartenze, Fixtures.romaPartenze)
                .flatMap { RfiParser.parseBoard(it, BoardMode.Departures).trains }
        val names = trains.flatMap { SpokenItalian.stops(it.info) + it.station }.distinct()
        val leftovers = names.map(places::spoken).filter { '.' in it }
        // Ne restano poche, troncate in modo che nessuna regola le ricostruisca: se ne compaiono
        // di nuove conviene aggiungere una regola in SpokenItalian.
        assertTrue(leftovers.size <= 8, "abbreviazioni non sciolte: $leftovers")
    }
}
