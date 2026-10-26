package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchModelsTest {

    private val dir = Files.createTempDirectory("watch").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val selection = WatchSelection(
        StationSource.Rfi,
        Station("837", "BOLOGNA CENTRALE"),
        "91831",
        "R 91831 → VIGNOLA",
        time = "19:18",
    )

    private val train = WatchedTrain(
        carrier = "TRENITALIA TPER",
        category = "R",
        number = "91831",
        destination = "VIGNOLA",
        time = "19:18",
        delayMinutes = 5,
        platform = "7 OVEST",
        info = "FERMA A: CASALECCHIO DI RENO",
        stationName = "BOLOGNA CENTRALE",
    )

    private val onBoard = WatchSnapshot(selection, train, WatchStatus.OnBoard, updatedAt = 1_000)

    private val saronno = WatchSelection(
        StationSource.Fnm,
        Station("S01066", "MILANO CADORNA"),
        "2615",
        "R 2615 → SARONNO",
        time = "07:42",
    )

    private val toSaronno =
        train.copy(number = "2615", destination = "SARONNO", time = "07:42", delayMinutes = null)

    /** Ora italiana del 26 ottobre 2026. */
    private fun at(time: String, day: Int = 26): Instant =
        LocalDateTime.parse("2026-10-${day}T$time").toInstant(WatchPlan.ZONE)

    @Test
    fun `lo snapshot torna uguale dal JSON`() {
        assertEquals(onBoard, WatchSnapshot.decode(onBoard.encode()))
        val offline = onBoard.copy(status = WatchStatus.Error, error = "x", offline = WatchOffline.NoInternet)
        assertEquals(offline, WatchSnapshot.decode(offline.encode()))
    }

    @Test
    fun `i campi sconosciuti si ignorano, un JSON rotto no`() {
        val newer = """{"selection":null,"status":"NotOnBoard","updatedAt":5,"campoNuovo":42}"""
        assertEquals(WatchSnapshot(status = WatchStatus.NotOnBoard, updatedAt = 5), WatchSnapshot.decode(newer))
        assertNull(WatchSnapshot.decode("{"))
        assertNull(WatchSnapshot.decode("""{"status":"Sconosciuto"}"""))
    }

    @Test
    fun `i treni, anche nessuno, tornano uguali dal JSON`() {
        val trains = listOf(selection, saronno)
        assertEquals(trains, decodeWatchTrains(encodeWatchTrains(trains)))
        assertEquals(emptyList(), decodeWatchTrains(encodeWatchTrains(emptyList())))
        assertNull(decodeWatchTrains("non è JSON"))
    }

    @Test
    fun `la selezione porta l'orario, e quella sola di un telefono di prima si legge`() {
        assertEquals("19:18", WatchSelection.of(StationSource.Rfi, selection.station, train).time)
        val older =
            """{"selection":{"source":"Rfi","station":{"id":"837","name":"BOLOGNA CENTRALE"},""" +
                    """"trainNumber":"91831","label":"R 91831 → VIGNOLA"}}"""
        assertEquals(listOf(selection.copy(time = null)), decodeWatchTrains(older))
        assertEquals(emptyList(), decodeWatchTrains("""{"selection":null}"""))
    }

    @Test
    fun `la richiesta del watch porta il suo stato, quella di un watch di prima solo il numero`() {
        val request = WatchRefreshRequest("42", onBoard)
        val decoded = WatchRefreshRequest.decode(request.encode())
        assertEquals("42", decoded.request)
        assertEquals(onBoard, decoded.state)

        val older = WatchRefreshRequest.decode("-1234567")
        assertEquals("-1234567", older.request)
        assertEquals(WatchSnapshot(), older.state)
    }

    @Test
    fun `il treno scelto per numero si completa una volta sola, e i dati non tornano indietro`() {
        val chosen = WatchSelection.byNumber(StationSource.Rfi, selection.station, "91831")
        assertTrue(chosen.awaitingDetails)
        assertEquals("Treno 91831", chosen.label)

        val completed = chosen.completedWith(train)
        assertEquals(selection, completed)
        assertFalse(completed.awaitingDetails)
        assertEquals(
            completed,
            completed.completedWith(train.copy(destination = "MODENA", time = "20:00"))
        )

        assertTrue(chosen.sameTrain(completed))
        assertEquals(chosen.key, completed.key)
        assertFalse(chosen.sameTrain(completed.copy(trainNumber = "1")))
        assertFalse(chosen.sameTrain(null))
        assertEquals(completed, chosen.orDetailsOf(completed))
        assertEquals(chosen, chosen.orDetailsOf(completed.copy(trainNumber = "1")))
        assertEquals(completed, completed.orDetailsOf(chosen))
    }

    @Test
    fun `in ordine di partenza, i treni senza orario restano dove sono`() {
        val waiting = WatchSelection.byNumber(StationSource.Rfi, selection.station, "3901")
        assertEquals(
            listOf(saronno, waiting, selection),
            WatchPlan.ordered(listOf(selection, waiting, saronno))
        )
        // La giornata dei treni comincia alle 3: quello delle 00:20 viene dopo quello delle 23:50.
        val late = selection.copy(trainNumber = "1", time = "23:50")
        val night = selection.copy(trainNumber = "2", time = "00:20")
        val early = selection.copy(trainNumber = "3", time = "04:10")
        assertEquals(listOf(early, late, night), WatchPlan.ordered(listOf(night, late, early)))
        // Un treno già seguito non si aggiunge due volte, ma ne arrivano i dati.
        val chosen = WatchSelection.byNumber(StationSource.Rfi, selection.station, "91831")
        assertEquals(
            listOf(saronno, selection),
            WatchPlan.added(listOf(chosen, saronno), selection)
        )
        assertEquals(
            listOf(saronno, selection),
            WatchPlan.added(listOf(saronno, selection), chosen)
        )
    }

    @Test
    fun `si mostra il primo treno non ancora partito`() {
        val trains = listOf(selection, saronno)

        val morning = WatchSnapshot().planned(trains, at("07:00"))
        assertEquals(saronno, morning.selection)
        assertEquals(listOf(saronno, selection), morning.plan)
        assertEquals("2026-10-26", morning.day)
        // Passato da 20 minuti senza vederlo sparire: si passa al successivo, da zero.
        val noon = morning.copy(train = toSaronno, status = WatchStatus.NotOnBoard, updatedAt = 1)
            .planned(trains, at("12:00"))
        assertEquals(WatchSnapshot(selection, plan = noon.plan, day = noon.day), noon)
        // Partiti tutti: il primo, che è il prossimo di domani.
        assertEquals(saronno, noon.planned(trains, at("23:00")).selection)
        // Dopo le 3 è un altro giorno; prima no.
        val departed = noon.copy(departed = listOf(saronno.key))
        assertEquals(listOf(saronno.key), departed.planned(trains, at("02:00", day = 27)).departed)
        val nextDay = departed.planned(trains, at("07:00", day = 27))
        assertEquals(saronno, nextDay.selection)
        assertEquals(emptyList(), nextDay.departed)
    }

    @Test
    fun `sparito dal tabellone è partito, e tocca al successivo`() {
        val trains = listOf(selection, saronno)
        val start = WatchSnapshot().planned(trains, at("07:00"))
        val milano = toSaronno.copy(delayMinutes = 10)

        val waiting = start.observed(null, at("07:00"))
        assertEquals(saronno, waiting.selection)
        assertEquals(WatchStatus.NotOnBoard, waiting.status)
        val seen = waiting.observed(milano, at("07:30"))
        assertEquals(WatchStatus.OnBoard, seen.status)
        // In ritardo resta anche dopo i 20 minuti dall'orario.
        assertEquals(saronno, seen.planned(trains, at("08:10")).selection)

        val gone = seen.observed(null, at("07:55"))
        assertEquals(listOf(saronno.key), gone.departed)
        assertEquals(
            WatchSnapshot(
                selection,
                plan = gone.plan,
                departed = gone.departed,
                day = gone.day
            ), gone
        )
    }

    @Test
    fun `un treno scelto per numero, quando compare, va al suo posto`() {
        val chosen = WatchSelection.byNumber(StationSource.Rfi, selection.station, "91831")
        val start = WatchSnapshot().planned(listOf(chosen, saronno), at("06:00"))
        // Senza orario resta dove l'ha messo l'utente: per ora è il primo.
        assertEquals(chosen, start.selection)

        val found = start.observed(train, at("06:10"))
        assertEquals(listOf(saronno, selection), found.plan)
        assertEquals(saronno, found.selection)
        assertNull(found.train)

        // Il telefono rimanda ancora la scelta per numero: etichetta e orario restano.
        assertEquals(found, found.planned(listOf(chosen, saronno), at("06:10")))
    }

    @Test
    fun `un errore tiene l'ultimo treno visto`() {
        val failed = onBoard.failed("RFI ha risposto 503", at("19:00"))
        assertEquals(WatchStatus.Error, failed.status)
        assertEquals(train, failed.train)
        assertEquals("RFI ha risposto 503", failed.error)
    }

    @Test
    fun `lo snapshot sopravvive alla riapertura dello store`() = runBlocking {
        val path = File(dir, "nested/watch.json").absolutePath
        WatchStore(path).use { store ->
            assertEquals(WatchSnapshot(), store.load())
            store.update { onBoard }
        }

        assertEquals(onBoard, WatchStore(path).use { it.load() })
    }

    @Test
    fun `un file illeggibile vale come snapshot vuoto`() = runBlocking {
        val path = File(dir, "watch.json").apply { writeText("{rotto") }.absolutePath

        assertEquals(WatchSnapshot(), WatchStore(path).use { it.load() })
    }
}
