package com.takaotech.departureboard.wear.ongoing

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import com.takaotech.departureboard.watch.WatchSelection
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.watch.WatchStatus
import com.takaotech.departureboard.watch.WatchedTrain
import kotlin.test.Test
import kotlin.test.assertEquals

class OngoingTrainTest {

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
    )

    @Test
    fun `orario, ritardo e binario del treno in tabellone`() {
        val onBoard = WatchSnapshot(selection, train, WatchStatus.OnBoard, updatedAt = 1)
        assertEquals("19:18", ongoingStatusText(onBoard))
        assertEquals(
            "19:18 · +5' · BIN 7",
            ongoingStatusText(onBoard.copy(train = train.copy(delayMinutes = 5, platform = "7"))),
        )
        assertEquals(
            "19:18 · CANC",
            ongoingStatusText(onBoard.copy(train = train.copy(cancelled = true)))
        )
    }

    @Test
    fun `non in tabellone, in attesa`() {
        assertEquals("19:18 · in attesa", ongoingStatusText(WatchSnapshot(selection)))
        assertEquals(
            "19:18 · in attesa",
            ongoingStatusText(
                WatchSnapshot(
                    selection,
                    train,
                    WatchStatus.NotOnBoard,
                    updatedAt = 1
                )
            ),
        )
        assertEquals("in attesa", ongoingStatusText(WatchSnapshot(selection.copy(time = null))))
    }

    @Test
    fun `muso del treno dalla categoria`() {
        assertEquals(TrainNose.Etr1000, TrainNose.of("FR"))
        assertEquals(TrainNose.Etr1000, TrainNose.of("AV"))
        assertEquals(TrainNose.Etr460, TrainNose.of("FB"))
        assertEquals(TrainNose.Etr460, TrainNose.of("ES*"))
        assertEquals(TrainNose.E464, TrainNose.of("R"))
        assertEquals(TrainNose.E464, TrainNose.of("S12"))
        assertEquals(TrainNose.E464, TrainNose.of(null))
        // Treno non ancora visto: la categoria è nell'etichetta; scelto per numero, quella di base.
        val frecciarossa = selection.copy(label = "FR 9531 → NAPOLI C.LE")
        assertEquals(TrainNose.Etr1000, TrainNose.of(WatchSnapshot(frecciarossa)))
        assertEquals(
            TrainNose.E464,
            TrainNose.of(WatchSnapshot(selection.copy(label = "Treno 9531")))
        )
        assertEquals(
            TrainNose.Etr460,
            TrainNose.of(WatchSnapshot(frecciarossa, train.copy(category = "FB")))
        )
    }
}
