package fnm

import rfi.Station
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FnmParserTest {

    @Test
    fun `elenco stazioni senza le voci di gruppo`() {
        val stations = FnmParser.parseStations(FnmFixtures.stazioni)

        assertEquals(FnmFixtures.stations.size, stations.size)
        assertEquals(Station("S01416", "ABBADIA LARIANA"), stations.first())
        assertTrue(Station("S01067", "MILANO DOMODOSSOLA") in stations)
        assertTrue(Station("S01758", "LAMBRUGO-LURAGO D'ERBA") in stations)
        assertTrue(stations.none { it.id.isBlank() || "tutte le stazioni" in it.name })
    }

    @Test
    fun `treni di una stazione in partenza e in arrivo`() {
        val lists = FnmParser.parseStationDetails(FnmFixtures.domodossola)

        assertEquals(20, lists.departures.size)
        assertEquals(20, lists.arrivals.size)
        assertEquals(
            FnmListTrain("1902162", "REG", "2162", "MILANO CADORNA", "17:44"),
            lists.departures.first(),
        )
        // In arrivo l'orario è quello di arrivo; "Diretto a" resta la destinazione.
        assertEquals(
            FnmListTrain("1902162", "REG", "2162", "MILANO CADORNA", "17:43"),
            lists.arrivals.first(),
        )
        assertTrue(lists.departures.any { it.line == "S4" })
    }

    @Test
    fun `dettaglio html con orari effettivi e ritardo dal testo`() {
        val html = FnmParser.parseSubDetails(FnmFixtures.subDetail2162)
        assertEquals(setOf("102162", "1902162"), html.keys)

        val detail = FnmParser.parseSubDetail(html.getValue("1902162"))

        assertEquals("REG", detail.category)
        assertEquals("COMO LAGO", detail.origin)
        assertEquals("MILANO CADORNA", detail.destination)
        assertEquals(3, detail.delayMinutes)
        assertEquals(6, detail.stops.size)
        val borghi = detail.stops[1]
        assertEquals(
            FnmStop(null, "COMO BORGHI", "16:49", "16:51", "16:49", "16:54"),
            borghi,
        )
        // Fermata futura: l'orario mostrato è quello previsto.
        val domodossola = detail.stops.single { it.name == "MILANO DOMODOSSOLA" }
        assertEquals("17:47", domodossola.depEstimated)
        assertNull(domodossola.depActual)
        assertEquals("", domodossola.platform)
    }

    @Test
    fun `dettaglio bff scelto per codice trasporto`() {
        val solutions = FnmParser.parseBff(BffResponses.train2162())
        assertEquals(2, solutions.size)

        val detail = FnmParser.bffDetail(solutions, "1902162")!!

        assertEquals("REG", detail.category)
        assertEquals("TRENORD", detail.carrier)
        assertEquals("COMO LAGO", detail.origin)
        assertEquals("MILANO CADORNA", detail.destination)
        assertEquals(4, detail.delayMinutes)
        val domodossola = detail.stops.single { it.mir == FnmFixtures.DOMODOSSOLA_MIR }
        assertEquals("2", domodossola.platform)
        assertEquals("17:44:00", domodossola.depTime)
        assertEquals("17:48:00", domodossola.depEstimated)

        assertEquals("BOZZOLO", FnmParser.bffDetail(solutions, "102162")!!.origin)
        // Impresa con suffisso interno.
        val suffixed = BffResponses.response(
            BffResponses.solution(
                code = "1910066",
                category = "REG",
                operator = "TRENORD\$:\$FNM3",
                stops = listOf(
                    BffResponses.Stop("S01066", "MILANO CADORNA", dep = "17:40:00"),
                    BffResponses.Stop("S01800", "VARESE NORD", arr = "18:45:00"),
                ),
            )
        )
        assertEquals(
            "TRENORD",
            FnmParser.bffDetail(FnmParser.parseBff(suffixed), "1910066")!!.carrier
        )
        assertNull(FnmParser.bffDetail(solutions, "1900858"))
    }

    @Test
    fun `candidati della chiave nel javascript`() {
        val candidates = FnmParser.keyCandidates(FnmFixtures.webComponents)

        assertEquals(listOf("<", FnmKeyStore.DEFAULT_PASSPHRASE), candidates)
    }

    @Test
    fun `candidati con escape e apici singoli`() {
        val js =
            """decryptResponse(A){const t=this.generateKey(k),n=1};var k='a\'b\x41',x=k="cD";"""

        assertEquals(listOf("a'bA", "cD"), FnmParser.keyCandidates(js))
    }
}
