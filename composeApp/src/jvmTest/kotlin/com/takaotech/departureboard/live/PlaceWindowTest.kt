package com.takaotech.departureboard.live

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceWindowTest {

    private val area = DpSize(1400.dp, 900.dp)
    private val window = DpSize(560.dp, 260.dp)

    /** Bottone indietro e ricerca, col margine: in alto a sinistra, non tutta la riga. */
    private val keepOut = DpRect(0.dp, 0.dp, 512.dp, 88.dp)

    private fun place(x: Int, y: Int, area: DpSize = this.area) =
        placeWindow(DpOffset(x.dp, y.dp), window, area, keepOut)

    @Test
    fun `fuori dalla zona di ricerca la finestra resta dov'è`() {
        assertEquals(DpOffset(16.dp, 88.dp), place(16, 88))
        // A destra della ricerca può salire fino in cima.
        assertEquals(DpOffset(600.dp, 0.dp), place(600, 0))
    }

    @Test
    fun `sopra la ricerca si sposta del meno possibile, sotto o a destra`() {
        // Appena entrata dal basso: torna sotto.
        assertEquals(DpOffset(100.dp, 88.dp), place(100, 60))
        // Appena entrata da destra: torna a destra.
        assertEquals(DpOffset(512.dp, 10.dp), place(490, 10))
    }

    @Test
    fun `se a destra non c'è posto va sotto`() {
        val narrow = DpSize(800.dp, 900.dp)
        assertEquals(DpOffset(240.dp, 88.dp), place(400, 10, narrow))
    }

    @Test
    fun `resta nella schermata con almeno la barra del titolo`() {
        assertEquals(DpOffset(840.dp, 844.dp), place(5000, 5000))
        assertEquals(DpOffset(0.dp, 88.dp), place(-300, -500))
    }
}
