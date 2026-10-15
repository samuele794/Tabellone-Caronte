package com.takaotech.departureboard.rfi

import com.takaotech.departureboard.DotSampling
import com.takaotech.departureboard.Lamps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class RfiMapperTest {

    @Test
    fun `codici categoria`() {
        assertEquals("AV", RfiMapper.categoryCode("ALTA VELOCITA'"))
        assertEquals("R", RfiMapper.categoryCode("REG"))
        assertEquals("IC", RfiMapper.categoryCode("INTERCITY"))
        assertEquals("ES", RfiMapper.categoryCode("ES*"))
        assertEquals("BUS", RfiMapper.categoryCode("BUS"))
        assertEquals("FRE", RfiMapper.categoryCode("FRECCIAROSSA"), "sconosciuta: prime 3 lettere")
    }

    @Test
    fun `riga del tabellone`() {
        val board = RfiParser.parseBoard(Fixtures.napoliArrivi, BoardMode.Arrivals)
        val d = RfiMapper().departures(board).first()

        assertEquals("AV", d.category)
        assertEquals("9955", d.number)
        assertEquals("TORINO PORTA NUOVA", d.destination)
        assertEquals("23:49", d.time)
        assertEquals(10, d.delayMinutes)
        assertEquals("24", d.platform)
        assertEquals(Lamps.Blinking, d.lamps)
        assertEquals(DotSampling.Alpha, d.logoSampling)
        assertEquals(RfiMapper.LOGO_THRESHOLD, d.logoThreshold)
        assertNotNull(d.logo, "GIF decodificato")
    }

    @Test
    fun `logo riusati tra un aggiornamento e l'altro`() {
        val mapper = RfiMapper()
        val html = Fixtures.milanoPartenze
        val first = mapper.departures(RfiParser.parseBoard(html, BoardMode.Departures)).first()
        val second = mapper.departures(RfiParser.parseBoard(html, BoardMode.Departures)).first()

        assertSame(first.logo, second.logo)
    }
}
