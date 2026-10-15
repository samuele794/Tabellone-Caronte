package com.takaotech.departureboard.fnm

import com.takaotech.departureboard.rfi.BoardMode.Arrivals
import com.takaotech.departureboard.rfi.BoardMode.Departures
import com.takaotech.departureboard.rfi.Station
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FnmMapperTest {

    private val domodossola = Station(FnmFixtures.DOMODOSSOLA_MIR, "MILANO DOMODOSSOLA")
    private val now = LocalTime(17, 44)
    private val departure = FnmListTrain("1902162", "REG", "2162", "MILANO CADORNA", "17:44")
    private val arrival = departure.copy(time = "17:43")
    private val bff = FnmParser.bffDetail(FnmParser.parseBff(BffResponses.train2162()), "1902162")!!

    private fun FnmTrainDetail.withStop(mir: String, change: (FnmStop) -> FnmStop) =
        copy(stops = stops.map { if (it.mir == mir) change(it) else it })

    @Test
    fun `partenza dal bff con binario, ritardo e fermate successive`() {
        val t = FnmMapper.train(departure, bff, domodossola, Departures, now)!!

        assertEquals("TRENORD", t.carrier)
        assertEquals(TrenordLogo.BASE64, t.carrierLogoBase64)
        assertEquals("REG", t.category)
        assertEquals("2162", t.number)
        assertEquals("MILANO CADORNA", t.station)
        assertEquals("17:44", t.time)
        assertEquals(4, t.delayMinutes)
        assertEquals("2", t.platform)
        assertEquals("FERMA A: MILANO CADORNA (17:52)", t.info)
        assertFalse(t.flashing)
        assertFalse(t.cancelled)
    }

    @Test
    fun `arrivo con la provenienza`() {
        val t = FnmMapper.train(arrival, bff, domodossola, Arrivals, now)!!

        assertEquals("COMO LAGO", t.station)
        assertEquals("17:43", t.time)
        assertEquals(4, t.delayMinutes)
        assertEquals("", t.info)
    }

    @Test
    fun `spie accese con il treno in stazione o in arrivo`() {
        val inStation =
            bff.withStop(FnmFixtures.DOMODOSSOLA_MIR) { it.copy(arrActual = "17:47:20") }
        assertTrue(FnmMapper.train(departure, inStation, domodossola, Departures, now)!!.flashing)

        // Partito da Bovisa, arrivo previsto alle 17:47:30: alle 17:43 mancano più di 3 minuti.
        val approaching = bff.withStop(BffResponses.BOVISA_MIR) { it.copy(depActual = "17:43:10") }
        assertFalse(
            FnmMapper.train(
                departure,
                approaching,
                domodossola,
                Departures,
                LocalTime(17, 43)
            )!!.flashing
        )
        assertTrue(
            FnmMapper.train(
                departure,
                approaching,
                domodossola,
                Departures,
                LocalTime(17, 45)
            )!!.flashing
        )
    }

    @Test
    fun `treno gia partito o arrivato non si mostra`() {
        val departed = bff.withStop(FnmFixtures.DOMODOSSOLA_MIR) {
            it.copy(arrActual = "17:47:20", depActual = "17:48:05")
        }
        assertNull(FnmMapper.train(departure, departed, domodossola, Departures, now))
        assertNull(FnmMapper.train(arrival, departed, domodossola, Arrivals, now))
    }

    @Test
    fun `soppressione alla fermata`() {
        val cancelled = bff.withStop(FnmFixtures.DOMODOSSOLA_MIR) { it.copy(cancelled = true) }

        assertTrue(FnmMapper.train(departure, cancelled, domodossola, Departures, now)!!.cancelled)
    }

    @Test
    fun `dettaglio html senza binario`() {
        val html = FnmParser.parseSubDetail(
            FnmParser.parseSubDetails(FnmFixtures.subDetail2162).getValue("1902162")
        )

        val t = FnmMapper.train(departure, html, domodossola, Departures, now)!!

        assertEquals("MILANO CADORNA", t.station)
        assertEquals("17:44", t.time)
        assertEquals(3, t.delayMinutes)
        assertEquals("", t.platform)
        assertEquals("FERMA A: MILANO CADORNA (17:52)", t.info)
    }

    @Test
    fun `senza dettaglio restano i dati dell'elenco`() {
        val t = FnmMapper.train(departure, null, domodossola, Departures, now)!!

        assertEquals("REG", t.category)
        assertEquals("MILANO CADORNA", t.station)
        assertEquals("17:44", t.time)
        assertNull(t.delayMinutes)
        assertEquals("", t.platform)
        assertEquals("", FnmMapper.train(arrival, null, domodossola, Arrivals, now)!!.station)
    }

    @Test
    fun `orari a cavallo della mezzanotte`() {
        assertEquals(5, FnmMapper.minutesBetween("23:58", "00:03:00"))
        assertEquals(-2, FnmMapper.minutesUntil(LocalTime(0, 1), "23:59"))
        assertEquals("07:05", FnmMapper.hhmm("7:05:30"))
    }
}
