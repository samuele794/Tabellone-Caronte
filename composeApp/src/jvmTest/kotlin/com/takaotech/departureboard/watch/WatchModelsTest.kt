package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `la selezione, anche nessuna, torna uguale dal JSON`() {
        assertEquals(selection, decodeWatchSelection(encodeWatchSelection(selection)))
        assertNull(decodeWatchSelection(encodeWatchSelection(null)))
        assertNull(decodeWatchSelection("non è JSON"))
    }

    @Test
    fun `la selezione porta l'orario, e quelle salvate prima si leggono senza`() {
        assertEquals("19:18", WatchSelection.of(StationSource.Rfi, selection.station, train).time)
        val withTime = selection.copy(time = "19:18")
        assertEquals(withTime, decodeWatchSelection(encodeWatchSelection(withTime)))
        val older =
            """{"selection":{"source":"Rfi","station":{"id":"837","name":"BOLOGNA CENTRALE"},""" +
                    """"trainNumber":"91831","label":"R 91831 → VIGNOLA"}}"""
        assertEquals(selection, decodeWatchSelection(older))
    }

    @Test
    fun `in attesa si tiene l'ultimo treno visto della stessa selezione`() {
        val waiting = WatchSnapshot(selection, null, WatchStatus.NotOnBoard, updatedAt = 2_000)

        assertEquals(waiting.copy(train = train), waiting.after(onBoard))
        // Un'altra selezione non eredita il treno.
        val other = waiting.copy(selection = selection.copy(trainNumber = "1"))
        assertEquals(other, other.after(onBoard))
        // Un treno nuovo vince su quello vecchio.
        val fresh = onBoard.copy(train = train.copy(delayMinutes = 9))
        assertEquals(fresh, fresh.after(onBoard))
    }

    @Test
    fun `cambiando selezione si riparte da zero`() {
        assertEquals(onBoard, onBoard.withSelection(selection))
        assertEquals(WatchSnapshot(), onBoard.withSelection(null))
    }

    @Test
    fun `il treno scelto per numero si completa una volta sola, e i dati non tornano indietro`() {
        val chosen = WatchSelection.byNumber(StationSource.Rfi, selection.station, "91831")
        assertTrue(chosen.awaitingDetails)
        assertEquals("Treno 91831", chosen.label)

        val completed = chosen.completedWith(train)
        assertEquals(selection.copy(time = "19:18"), completed)
        assertFalse(completed.awaitingDetails)
        assertEquals(
            completed,
            completed.completedWith(train.copy(destination = "MODENA", time = "20:00"))
        )

        assertTrue(chosen.sameTrain(completed))
        assertFalse(chosen.sameTrain(completed.copy(trainNumber = "1")))
        assertFalse(chosen.sameTrain(null))
        assertEquals(completed, chosen.orDetailsOf(completed))
        assertEquals(chosen, chosen.orDetailsOf(completed.copy(trainNumber = "1")))
        assertEquals(completed, completed.orDetailsOf(chosen))
    }

    @Test
    fun `lo stesso treno, anche con la selezione completata, tiene i dati`() {
        val chosen = WatchSelection.byNumber(StationSource.Rfi, selection.station, "91831")
        val completed = chosen.completedWith(train)
        val seen = WatchSnapshot(completed, train, WatchStatus.OnBoard, updatedAt = 1_000)

        // Il telefono rimanda ancora la scelta per numero: il watch non perde etichetta e orario.
        assertEquals(seen, seen.withSelection(chosen))
        // La selezione completata arriva su uno snapshot che ancora non l'aveva.
        val waiting = WatchSnapshot(chosen, status = WatchStatus.NotOnBoard, updatedAt = 500)
        assertEquals(waiting.copy(selection = completed), waiting.withSelection(completed))
        // Un controllo che non l'ha trovato tiene i dati del controllo precedente.
        val gone = WatchSnapshot(chosen, null, WatchStatus.NotOnBoard, updatedAt = 2_000)
        assertEquals(gone.copy(selection = completed, train = train), gone.after(seen))
        assertEquals(seen, seen.after(waiting))
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
