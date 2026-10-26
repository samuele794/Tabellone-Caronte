package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
