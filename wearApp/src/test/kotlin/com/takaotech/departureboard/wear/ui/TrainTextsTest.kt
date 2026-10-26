package com.takaotech.departureboard.wear.ui

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.WatchOffline
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import com.takaotech.departureboard.watch.WatchedTrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrainTextsTest {

    private val train = WatchedTrain(
        carrier = "TRENITALIA",
        category = "ICN",
        number = "752",
        destination = "MILANO C.LE",
        time = "06:20",
        info = "FERMA A: BOLOGNA",
    )

    private val selection = WatchSelection(StationSource.Rfi, Station("1", "ROMA"), "752", "ICN 752 → MILANO C.LE")

    @Test
    fun `ritardo in minuti, cancellazione o testo del monitor`() {
        assertEquals("220'", delayText(train.copy(delayMinutes = 220)))
        assertEquals("CANCELLATO", delayText(train.copy(cancelled = true, delayMinutes = 5)))
        assertEquals("RITARDO", delayText(train.copy(delayText = "Ritardo")))
        assertNull(delayText(train.copy(delayMinutes = 0)))
        assertNull(delayText(null))
    }

    @Test
    fun `informazioni o perché mancano`() {
        val onBoard = WatchSnapshot(selection, train, WatchStatus.OnBoard, updatedAt = 1)
        assertEquals("FERMA A: BOLOGNA", infoText(onBoard))
        assertEquals("IN ATTESA: IL TRENO NON È ANCORA IN TABELLONE", infoText(onBoard.copy(status = WatchStatus.NotOnBoard)))
        assertEquals("MONITOR NON DISPONIBILE", infoText(onBoard.copy(status = WatchStatus.Error)))
        assertEquals("IN ATTESA DEI DATI", infoText(WatchSnapshot(selection)))
    }

    @Test
    fun `partenza con il ritardo in fondo al modulo`() {
        assertEquals("06:20     ", departureLed("06:20", train))
        assertEquals("06:20 220'", departureLed("06:20", train.copy(delayMinutes = 220)))
        assertEquals("06:20   5'", departureLed("06:20", train.copy(delayMinutes = 5)))
        assertEquals("06:20 CANC", departureLed("06:20", train.copy(cancelled = true, delayMinutes = 5)))
        assertEquals("--:--     ", departureLed("--:--", null))
    }

    @Test
    fun `stato compatto per il tile`() {
        val at = 1_000_000L
        val snapshot = WatchSnapshot(selection, train, WatchStatus.OnBoard, updatedAt = at)
        assertEquals("aggiornato adesso", compactStatusText(snapshot, at))
        assertEquals("12 min fa · senza rete", compactStatusText(snapshot.copy(offline = WatchOffline.NoNetwork), at + 12 * 60_000))
        assertEquals("in attesa", compactStatusText(WatchSnapshot(selection), at))
        assertEquals("senza internet", compactStatusText(WatchSnapshot(selection, offline = WatchOffline.NoInternet), at))
    }

    @Test
    fun `binario, ritardo e spia solo per il treno in tabellone`() {
        val onBoard = WatchSnapshot(selection, train, WatchStatus.OnBoard, updatedAt = 1)
        assertEquals(train, onBoard.liveTrain())
        assertNull(onBoard.copy(status = WatchStatus.NotOnBoard).liveTrain())
        assertNull(onBoard.copy(status = WatchStatus.Error).liveTrain())
    }
}
