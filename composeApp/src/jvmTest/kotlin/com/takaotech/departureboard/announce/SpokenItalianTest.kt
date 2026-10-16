package com.takaotech.departureboard.announce

import com.takaotech.departureboard.announce.SpokenItalian.place
import com.takaotech.departureboard.announce.SpokenItalian.platform
import com.takaotech.departureboard.announce.SpokenItalian.time
import com.takaotech.departureboard.announce.SpokenItalian.trainNumber
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.Fixtures
import com.takaotech.departureboard.rfi.RfiParser
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
    fun `numeri cardinali per le note`() {
        assertEquals("cento", SpokenItalian.cardinal(100))
        assertEquals("centotto", SpokenItalian.cardinal(108))
        assertEquals("centottanta", SpokenItalian.cardinal(180))
        assertEquals("duecentosessantuno", SpokenItalian.cardinal(261))
        assertEquals("mille", SpokenItalian.cardinal(1000))
        assertEquals("milleduecentotrentaquattro", SpokenItalian.cardinal(1234))
        assertEquals("ventunomila", SpokenItalian.cardinal(21000))
    }

    @Test
    fun `numero del treno secondo il MAS`() {
        assertEquals("ottantaquattro", trainNumber("84"))
        assertEquals("sette cinque tre", trainNumber("753"))
        assertEquals("novantaquattro quarantotto", trainNumber("9448"))
        assertEquals("dodici uno cinque sei", trainNumber("12156"))
        assertEquals("novantaquattro zero cinque", trainNumber("9405"))
        assertEquals("dodici quarantasette a", trainNumber("1247A"), "suffisso dei bus RFI")
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
    fun `binari, anche con i suffissi del monitor`() {
        assertEquals("tre", platform("3"))
        assertEquals("ventiquattro", platform("24"))
        assertEquals("undici tronco", platform("11TR"))
        assertEquals("uno ovest", platform("1-OVEST"))
        assertEquals("tre ovest", platform("3 OVEST"))
        assertEquals("due est", platform("2EST"))
        assertEquals("uno sotterraneo", platform("1 SOT"))
        assertEquals("venti bi", platform("20B"))
        assertEquals("pi effe", platform("PF"))
    }

    @Test
    fun `sigle compitate`() {
        assertEquals("effe emme tre", SpokenItalian.spell("FM3"))
        assertEquals("esse tredici", SpokenItalian.spell("S13"))
        assertEquals("erre i lunga", SpokenItalian.spell("RJ"))
    }

    @Test
    fun `nomi di località`() {
        assertEquals("Bari Centrale", place("BARI C.LE"))
        assertEquals("Agrigento Centrale", place("AGRIGENTO CENTR."))
        assertEquals("Torino Porta Nuova", place("TORINO P.NUOVA"))
        assertEquals("Torino Porta Susa", place("TORINO P. SUSA"))
        assertEquals("Torino Porta Nuova", place("TORINO PORTA NUOVA"))
        assertEquals("San Giovanni", place("S.GIOVANNI"))
        assertEquals("Santa Maria", place("S.MARIA"))
        assertEquals("San Nicola", place("S.NICOLA"))
        assertEquals("Sant'Anna", place("S.ANNA"))
        assertEquals("Sant'Ambrogio", place("S. AMBROGIO"))
        assertEquals("Santo Stefano", place("S.STEFANO"))
        assertEquals("Trinitapoli-San Ferdinando", place("TRINITAPOLI-S.FERDINANDO"))
        assertEquals("Firenze Santa Maria Novella", place("FIRENZE S.M.N."))
        assertEquals("Firenze Santa Maria Novella", place("FIRENZE SMN"))
        assertEquals("Firenze Campo di Marte", place("FIRENZE CM"))
        assertEquals("Reggio Emilia Alta Velocità Mediopadana", place("REGGIO E. AV MP."))
    }

    @Test
    fun `sigle di città in testa al nome`() {
        assertEquals("Milano Porta Garibaldi", place("MI.P.GARIBALDI"))
        assertEquals("Milano Porta Venezia", place("MI. P. VENEZIA"))
        assertEquals("Venezia Mestre", place("VE MESTRE"))
        assertEquals("Palermo Brancaccio", place("PA BRANCACCIO"))
        assertEquals("Torino Lingotto", place("TO.LINGOTTO"))
        assertEquals("Bari Torre a Mare", place("BA.TORRE A MARE"))
        assertEquals("Mi", place("MI"), "da sola non è una sigla di città")
    }

    @Test
    fun `apostrofi e accenti`() {
        assertEquals("Cassano D'Adda", place("CASSANO D'ADDA"))
        assertEquals("Cassano D'Adda", place("CASSANO D`ADDA"))
        assertEquals("Ponte D'Adda", place("PONTE D''ADDA"))
        assertEquals("Santhià", place("SANTHIA'"))
        assertEquals("San Donà di Piave", place("S.DONA'' DI PIAVE"))
        assertEquals("Santa Croce", place("S.CROCE"))
        assertEquals("Isola della Scala", place("ISOLA DELLA SCALA"))
        assertEquals("Torre Annunziata Città", place("TORRE ANNUNZIATA CITTA'"))
    }

    @Test
    fun `categoria commerciale`() {
        assertEquals("Alta Velocità", SpokenItalian.category("ALTA VELOCITA'"))
        assertEquals("Regionale", SpokenItalian.category("REG"))
        assertEquals("Regionale Veloce", SpokenItalian.category("RV"))
        assertEquals("RegioExpress", SpokenItalian.category("RE"))
        assertEquals("Eurositi", SpokenItalian.category("EC"))
        assertEquals("Intersiti", SpokenItalian.category("IC"))
        assertEquals("Intersiti Notte", SpokenItalian.category("INTERCITY NOTTE"))
        assertEquals("Eurostar", SpokenItalian.category("ES*"))
        assertEquals("Suburbano esse cinque", SpokenItalian.category("S5"))
        assertEquals("effe emme tre", SpokenItalian.category("FM3"))
        assertEquals("a erre elle", SpokenItalian.category("ARL"))
        assertEquals("", SpokenItalian.category(""))
        assertEquals(
            "Frecciarossa",
            SpokenItalian.category("FRECCIAROSSA"),
            "sconosciuta: per esteso"
        )
    }

    @Test
    fun `impresa ferroviaria, anche al posto del marchio`() {
        assertEquals("Trenitalia", SpokenItalian.carrier("TRENITALIA"))
        assertEquals("Trenitalia", SpokenItalian.carrier("FRECCIAROSSA"))
        assertEquals("Trenitalia", SpokenItalian.carrier("FRECCIARGENTO"))
        assertEquals("Trenitalia", SpokenItalian.carrier("INTERCITY NOTTE"))
        assertEquals("Trenitalia", SpokenItalian.carrier("LEONARDO EXPRESS"))
        assertEquals("Italo", SpokenItalian.carrier("ITALO"))
        assertEquals("Italo", SpokenItalian.carrier("NTV"))
        assertEquals("Trenòrd", SpokenItalian.carrier("TRENORD"))
        assertEquals("Trenitalia Tper", SpokenItalian.carrier("TRENITALIA TPER"))
        assertEquals("Ente Autonomo Volturno", SpokenItalian.carrier("ENTE AUTONOMO VOLTURNO"))
    }

    @Test
    fun `fermate dalla colonna informazioni`() {
        val bergamo =
            RfiParser.parseBoard(Fixtures.milanoPartenze, BoardMode.Departures).trains.first()
        assertEquals(
            listOf(
                "MILANO LAMBRATE",
                "PIOLTELLO LIM.",
                "TREVIGLIO OVEST",
                "VERDELLO DALMINE",
                "BERGAMO"
            ),
            SpokenItalian.stops(bergamo.info),
        )
        assertEquals(emptyList(), SpokenItalian.stops(""))
    }

    @Test
    fun `frasi libere delle note`() {
        assertEquals(
            "Viaggiatori da aversa con bus sostitutivo alle ore sedici e trenta",
            SpokenItalian.sentence("VIAGGIATORI DA AVERSA CON BUS SOSTITUTIVO ALLE ORE 16:30"),
        )
        assertEquals(
            "Viaggiatori da potenza Centrale con bus sostitutivo alle ore diciassette",
            SpokenItalian.sentence("VIAGGIATORI DA POTENZA C.LE CON BUS SOSTITUTIVO ALLE ORE 17:00"),
        )
        assertEquals("Gate bi", SpokenItalian.sentence("GATE B"))
        assertEquals("Frecciarossa mille", SpokenItalian.sentence("FRECCIAROSSA 1000"))
    }
}
