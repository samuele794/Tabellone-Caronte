package announce

import rfi.BoardMode.Arrivals
import rfi.BoardMode.Departures
import rfi.Fixtures
import rfi.RfiParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardAnnouncementsTest {

    private val milano = "1728"
    private val napoli = "1951"

    private fun BoardAnnouncements.codes(
        board: rfi.RfiBoard,
        station: String = milano,
        mode: rfi.BoardMode = Departures
    ) =
        update(station, mode, board).map { "${it.code} ${it.train.number}" }

    @Test
    fun `il primo tabellone non annuncia nulla`() {
        val tracker = BoardAnnouncements()
        val board = RfiParser.parseBoard(Fixtures.napoliArrivi, Arrivals)
        assertTrue(board.trains.any { it.flashing }, "la pagina ha treni in arrivo")
        assertEquals(emptyList(), tracker.codes(board, napoli, Arrivals))
        assertEquals(
            emptyList(),
            tracker.codes(board, napoli, Arrivals),
            "stesso tabellone, nessuna novità"
        )
    }

    @Test
    fun `cambio di stazione o di modalità fa da nuova base`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train()))
        val flashing = board(train(flashing = true, delayMinutes = 30))
        assertEquals(emptyList(), tracker.codes(flashing, station = napoli))
        assertEquals(
            emptyList(),
            tracker.codes(board(train(flashing = true)), station = napoli, mode = Arrivals)
        )
        assertEquals(emptyList(), tracker.codes(board(train(delayMinutes = 30)), station = milano))
    }

    @Test
    fun `treno in partenza`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(), train(number = "2061")))
        assertEquals(
            listOf("P1 2061"),
            tracker.codes(board(train(), train(number = "2061", flashing = true)))
        )
        assertEquals(
            emptyList(),
            tracker.codes(board(train(), train(number = "2061", flashing = true))),
            "già annunciato"
        )
    }

    @Test
    fun `treno comparso non entra nel delta`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train()))
        val added = board(
            train(),
            train(number = "2061", flashing = true, platform = "3", delayMinutes = 20, info = "x")
        )
        assertEquals(emptyList(), tracker.codes(added))
        assertEquals(
            emptyList(),
            tracker.codes(added),
            "dal giro dopo è nella base: nessuna novità"
        )
        val later = board(
            train(),
            train(number = "2061", flashing = true, platform = "3", delayMinutes = 25)
        )
        assertEquals(
            emptyList(),
            tracker.codes(later),
            "ritardo +5 su un treno in partenza: già detto"
        )
    }

    @Test
    fun `ritardo già visibile quando il treno compare non si annuncia`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train()))
        tracker.update(
            milano,
            Departures,
            board(train(), train(number = "2061", delayMinutes = 12))
        )
        assertEquals(
            emptyList(),
            tracker.codes(board(train(), train(number = "2061", delayMinutes = 13)))
        )
        assertEquals(
            listOf("P5 2061"),
            tracker.codes(board(train(), train(number = "2061", delayMinutes = 17)))
        )
    }

    @Test
    fun `treno sparito e ricomparso riparte dalla base`() {
        val tracker = BoardAnnouncements()
        tracker.update(
            milano,
            Departures,
            board(train(), train(number = "2061", delayMinutes = 30))
        )
        tracker.update(milano, Departures, board(train()))
        assertEquals(
            emptyList(),
            tracker.codes(
                board(
                    train(),
                    train(number = "2061", delayMinutes = 10, flashing = true)
                )
            )
        )
    }

    @Test
    fun `variazione di binario`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(platform = "8")))
        val changed = tracker.update(milano, Departures, board(train(platform = "11")))
        assertEquals(
            listOf(Announcement.PlatformChange(changed.single().train, Departures, "8")),
            changed
        )

        val departing =
            tracker.update(milano, Departures, board(train(platform = "12", flashing = true)))
        assertEquals("P2", departing.single().code)
        assertEquals("11", (departing.single() as Announcement.Imminent).previousPlatform)
    }

    @Test
    fun `binario assegnato`() {
        val tracker = BoardAnnouncements()
        tracker.update(
            milano,
            Departures,
            board(train(platform = ""), train(number = "2061", platform = ""))
        )
        val assigned = tracker.update(
            milano,
            Departures,
            board(train(platform = "11"), train(number = "2061", platform = ""))
        )
        assertEquals(
            listOf(Announcement.PlatformAssigned(assigned.single().train, Departures)),
            assigned
        )
        assertEquals(
            emptyList(),
            tracker.codes(board(train(platform = "11"), train(number = "2061", platform = ""))),
            "già annunciato"
        )
    }

    @Test
    fun `binario già presente nel tabellone di base o su un treno nuovo non si annuncia`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(platform = "11")))
        assertEquals(
            emptyList(),
            tracker.codes(board(train(platform = "11"), train(number = "2061", platform = "3")))
        )
    }

    @Test
    fun `binario assegnato mentre il treno è già in partenza`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(platform = "")))
        assertEquals(
            listOf("P1 9915"),
            tracker.codes(board(train(platform = "11", flashing = true)))
        )
    }

    @Test
    fun `ritardo da 10 minuti e poi a scatti di 5`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(delayMinutes = 5)))
        assertEquals(emptyList(), tracker.codes(board(train(delayMinutes = 9))))
        val first = tracker.update(milano, Departures, board(train(delayMinutes = 10)))
        assertEquals(Announcement.Delay(first.single().train, Departures, 10, null), first.single())
        assertEquals(emptyList(), tracker.codes(board(train(delayMinutes = 14))))
        val second = tracker.update(milano, Departures, board(train(delayMinutes = 15)))
        assertEquals(10, (second.single() as Announcement.Delay).previouslyAnnounced)
    }

    @Test
    fun `soppressione annunciata una volta`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Arrivals, board(train(), mode = Arrivals))
        assertEquals(
            listOf("A5 9915"),
            tracker.codes(board(train(cancelled = true), mode = Arrivals), mode = Arrivals)
        )
        assertEquals(
            emptyList(),
            tracker.codes(board(train(cancelled = true), mode = Arrivals), mode = Arrivals)
        )
    }

    @Test
    fun `treni spariti non si annunciano`() {
        val tracker = BoardAnnouncements()
        tracker.update(milano, Departures, board(train(flashing = true), train(number = "2061")))
        assertEquals(emptyList(), tracker.codes(board(train(number = "2061"))))
    }
}
