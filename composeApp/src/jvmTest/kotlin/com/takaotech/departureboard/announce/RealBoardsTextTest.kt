package com.takaotech.departureboard.announce

import com.takaotech.departureboard.announce.AnnouncementText.text
import com.takaotech.departureboard.rfi.BoardMode.Departures
import com.takaotech.departureboard.rfi.Fixtures
import com.takaotech.departureboard.rfi.RfiParser
import com.takaotech.departureboard.rfi.RfiTrain
import kotlin.test.Test
import kotlin.test.assertEquals

/** Testi completi per treni veri delle pagine salvate il 14/10/2026, con l'elenco delle stazioni. */
class RealBoardsTextTest {

    private val places = PlaceNames(RfiParser.parseStations(Fixtures.stazioni).map { it.name })

    private fun train(html: String, number: String): RfiTrain =
        RfiParser.parseBoard(html, Departures).trains.single { it.number == number }

    private fun departing(t: RfiTrain) = text(Announcement.Imminent(t, Departures), places)

    @Test
    fun `Italo - categoria e impresa come nel MAS, composizione dopo le fermate`() {
        assertEquals(
            "Il treno Alta Velocità ottantanove ventinove di Italo, delle ore quattordici e ventotto, " +
                    "per Napoli Centrale, è in partenza in ritardo, dal binario tredici. " +
                    "Ferma a Roma Tiburtina e Roma Termini. La carrozza uno è in coda al treno.",
            departing(train(Fixtures.firenzePartenze, "8929")),
        )
    }

    @Test
    fun `Frecciarossa - il marchio diventa l'impresa Trenitalia`() {
        val t = train(Fixtures.firenzePartenze, "9540")
        assertEquals(
            "Il treno Alta Velocità novantacinque quaranta di Trenitalia, delle ore quindici e cinquantacinque, " +
                    "per Milano Centrale, è in partenza. " +
                    "Ferma a Bologna Centrale, Reggio Emilia Alta Velocità Mediopadana e Milano Rogoredo. " +
                    "La carrozza uno è in coda al treno.",
            departing(t),
        )
    }

    @Test
    fun `Leonardo Express - NO-STOP`() {
        assertEquals(
            "Il treno Regionale Veloce quarantasei trentaquattro di Trenitalia, delle ore quattordici e cinquanta, " +
                    "per Fiumicino Aeroporto, è in partenza, dal binario ventiquattro. " +
                    "Non sono previste fermate intermedie.",
            departing(train(Fixtures.romaPartenze, "4634")),
        )
    }

    @Test
    fun `linea suburbana - binario sotterraneo e destinazione abbreviata in fondo alle fermate`() {
        assertEquals(
            "Il treno Suburbano esse tredici ventiquattro tre quattro otto di Trenord, delle ore quattordici e cinquantasei, " +
                    "per Garbagnate Milanese, è in partenza, dal binario due sotterraneo. " +
                    "Ferma a Milano Lancetti, Milano Bovisa P. e Bollate Centro.",
            departing(train(Fixtures.garibaldiPartenze, "24348")),
        )
    }

    @Test
    fun `VIA nelle note - relazioni di percorrenza dopo la destinazione`() {
        val t = train(Fixtures.garibaldiPartenze, "2271")
        assertEquals(
            "Informiamo i viaggiatori che il treno RegioExpress ventidue settantuno di Trenord, " +
                    "delle ore quindici e venti, per Bergamo, via Villapizzone e Milano Lambrate, partirà dal binario tre.",
            text(Announcement.PlatformAssigned(t.copyPlatform("3"), Departures), places),
        )
    }

    @Test
    fun `nota libera letta come frase, BORGO S_L_ in fondo è la destinazione`() {
        assertEquals(
            "Il treno Regionale diciotto nove cinque nove di Trenitalia, delle ore quattordici e cinquantacinque, " +
                    "per Borgo San Lorenzo, è in partenza, dal binario diciassette. " +
                    "Ferma a Firenze Campo di Marte, Sieci, Pontassieve, Rufina, Scopeti, Contea Londa, " +
                    "Dicomano, Vicchio e Borgo San Lorenzo Rimorelli. Binario decentrato.",
            departing(train(Fixtures.firenzePartenze, "18959")),
        )
    }

    @Test
    fun `P9 dal monitor - Cancellato`() {
        val t = train(Fixtures.firenzePartenze, "18377")
        assertEquals("P9", Announcement.Cancelled(t, Departures).code)
        assertEquals(
            "Annuncio cancellazione treno! Il treno Regionale diciotto tre sette sette di Trenitalia, " +
                    "previsto in partenza alle ore quattordici e cinquantatré, per La Spezia Centrale, " +
                    "oggi non sarà effettuato. Ci scusiamo per il disagio.",
            text(Announcement.Cancelled(t, Departures), places),
        )
    }

    private fun RfiTrain.copyPlatform(platform: String) = RfiTrain(
        carrier, carrierLogoBase64, category, number, station, time, delayMinutes, platform,
        flashing, info, notes, cancelled,
    )
}
