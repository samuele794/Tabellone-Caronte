package com.takaotech.departureboard.announce

import com.takaotech.departureboard.announce.AnnouncementText.text
import com.takaotech.departureboard.rfi.BoardMode.Arrivals
import com.takaotech.departureboard.rfi.BoardMode.Departures
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnouncementTextTest {

    private val bari =
        train(info = "FERMA A:BOLOGNA C.LE (9:30) - ANCONA (11:20) - PESCARA C.LE (12:40) - BARI C.LE (15:00)")
    private val napoli =
        train(number = "9955", station = "TORINO PORTA NUOVA", time = "23:49", platform = "24")

    @Test
    fun `P1 partenza`() {
        val a = Announcement.Imminent(bari, Departures)
        assertEquals("P1", a.code)
        assertEquals(
            "Il treno Alta Velocità novantanove quindici di Trenitalia, delle ore sette e quaranta, " +
                    "per Bari Centrale, è in partenza, dal binario undici. " +
                    "Ferma a Bologna Centrale, Ancona e Pescara Centrale.",
            text(a),
        )
    }

    @Test
    fun `P1 in ritardo oltre 15 minuti, senza fermate intermedie`() {
        val t = train(delayMinutes = 20, info = "FERMA A:BARI C.LE (15:00)")
        assertEquals(
            "Il treno Alta Velocità novantanove quindici di Trenitalia, delle ore sette e quaranta, " +
                    "per Bari Centrale, è in partenza in ritardo, dal binario undici. " +
                    "Non sono previste fermate intermedie.",
            text(Announcement.Imminent(t, Departures)),
        )
    }

    @Test
    fun `P2 partenza con variazione di binario`() {
        val a = Announcement.Imminent(train(), Departures, previousPlatform = "8")
        assertEquals("P2", a.code)
        assertEquals(
            "Il treno Alta Velocità novantanove quindici di Trenitalia, delle ore sette e quaranta, " +
                    "per Bari Centrale, è in partenza, dal binario undici, invece che dal binario otto.",
            text(a),
        )
    }

    @Test
    fun `P16 autobus sostitutivo`() {
        val bus = train(
            number = "1247A",
            category = "BUS",
            carrier = "TRENORD",
            station = "BERGAMO",
            time = "00:05",
            platform = "PE"
        )
        val a = Announcement.Imminent(bus, Departures)
        assertEquals("P16", a.code)
        assertEquals(
            "L'autobus sostitutivo dodici quarantasette a di Trenord, delle ore zero e zero cinque, " +
                    "per Bergamo, è previsto in partenza dal piazzale antistante la stazione.",
            text(a),
        )
    }

    @Test
    fun `P5 preavviso di ritardo`() {
        val a = Announcement.Delay(
            train(delayMinutes = 25),
            Departures,
            minutes = 25,
            previouslyAnnounced = 10
        )
        assertEquals("P5", a.code)
        assertEquals(
            "Annuncio ritardo! Il treno Alta Velocità novantanove quindici di Trenitalia, delle ore sette e quaranta, " +
                    "per Bari Centrale, partirà dal binario undici con un ritardo previsto di venticinque minuti, " +
                    "diversamente da quanto già annunciato. Ci scusiamo per il disagio.",
            text(a),
        )
    }

    @Test
    fun `P8 variazione di binario`() {
        val a = Announcement.PlatformChange(train(), Departures, previousPlatform = "8")
        assertEquals("P8", a.code)
        assertEquals(
            "Informiamo i viaggiatori che il treno Alta Velocità novantanove quindici di Trenitalia, " +
                    "delle ore sette e quaranta, per Bari Centrale, partirà dal binario undici invece che dal binario otto.",
            text(a),
        )
    }

    @Test
    fun `P8 binario assegnato`() {
        val a = Announcement.PlatformAssigned(train(), Departures)
        assertEquals("P8", a.code)
        assertEquals(
            "Informiamo i viaggiatori che il treno Alta Velocità novantanove quindici di Trenitalia, " +
                    "delle ore sette e quaranta, per Bari Centrale, partirà dal binario undici.",
            text(a),
        )
    }

    @Test
    fun `P9 soppressione`() {
        val a = Announcement.Cancelled(train(), Departures)
        assertEquals("P9", a.code)
        assertEquals(
            "Annuncio cancellazione treno! Il treno Alta Velocità novantanove quindici di Trenitalia, " +
                    "previsto in partenza alle ore sette e quaranta, per Bari Centrale, oggi non sarà effettuato. " +
                    "Ci scusiamo per il disagio.",
            text(a),
        )
    }

    @Test
    fun `A1 arrivo`() {
        val a = Announcement.Imminent(napoli, Arrivals)
        assertEquals("A1", a.code)
        assertEquals(
            "Il treno Alta Velocità novantanove cinquantacinque di Trenitalia, delle ore ventitré e quarantanove, " +
                    "proveniente da Torino Porta Nuova, è in arrivo al binario ventiquattro. " +
                    "Attenzione! Allontanarsi dalla linea gialla.",
            text(a),
        )
    }

    @Test
    fun `A3 preavviso di arrivo in ritardo`() {
        val a = Announcement.Delay(napoli, Arrivals, minutes = 10)
        assertEquals("A3", a.code)
        assertEquals(
            "Annuncio ritardo! Il treno Alta Velocità novantanove cinquantacinque di Trenitalia, " +
                    "delle ore ventitré e quarantanove, proveniente da Torino Porta Nuova, " +
                    "arriverà con un ritardo previsto di dieci minuti.",
            text(a),
        )
    }

    @Test
    fun `A4 binario assegnato`() {
        val a = Announcement.PlatformAssigned(napoli, Arrivals)
        assertEquals("A4", a.code)
        assertEquals(
            "Informiamo i viaggiatori che il treno Alta Velocità novantanove cinquantacinque di Trenitalia, " +
                    "delle ore ventitré e quarantanove, proveniente da Torino Porta Nuova, arriverà al binario ventiquattro.",
            text(a),
        )
    }

    @Test
    fun `A4 e A5`() {
        assertEquals(
            "Informiamo i viaggiatori che il treno Alta Velocità novantanove cinquantacinque di Trenitalia, " +
                    "delle ore ventitré e quarantanove, proveniente da Torino Porta Nuova, " +
                    "arriverà al binario ventiquattro invece che al binario venti.",
            text(Announcement.PlatformChange(napoli, Arrivals, previousPlatform = "20")),
        )
        assertEquals(
            "Annuncio cancellazione treno! Il treno Alta Velocità novantanove cinquantacinque di Trenitalia, " +
                    "previsto in arrivo da Torino Porta Nuova, alle ore ventitré e quarantanove, " +
                    "oggi non è stato effettuato. Ci scusiamo per il disagio.",
            text(Announcement.Cancelled(napoli, Arrivals)),
        )
    }
}
