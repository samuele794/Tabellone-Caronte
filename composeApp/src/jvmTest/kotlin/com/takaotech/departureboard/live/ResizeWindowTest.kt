package com.takaotech.departureboard.live

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ResizeWindowTest {

    private val area = DpSize(1400.dp, 900.dp)

    /** Finestra larga il doppio di quanto è alta. */
    private val size = DpSize(560.dp, 280.dp)
    private val offset = DpOffset(200.dp, 100.dp)

    private fun resize(edge: WindowEdge, x: Int, y: Int = 0, offset: DpOffset = this.offset) =
        resizeWindow(edge, DpOffset(x.dp, y.dp), offset, size, area)

    @Test
    fun `dal bordo destro si allarga verso destra`() {
        assertEquals(DpOffset(200.dp, 100.dp) to 600.dp, resize(WindowEdge.Right, 40))
        assertEquals(DpOffset(200.dp, 100.dp) to 500.dp, resize(WindowEdge.Right, -60))
    }

    @Test
    fun `dal bordo sinistro il lato destro resta fermo`() {
        assertEquals(DpOffset(160.dp, 100.dp) to 600.dp, resize(WindowEdge.Left, -40))
        assertEquals(DpOffset(260.dp, 100.dp) to 500.dp, resize(WindowEdge.Left, 60))
    }

    @Test
    fun `dal basso cresce l'altezza, e la larghezza in proporzione`() {
        assertEquals(DpOffset(200.dp, 100.dp) to 600.dp, resize(WindowEdge.Bottom, 0, 20))
    }

    @Test
    fun `da un angolo conta lo spostamento più ampio`() {
        assertEquals(600.dp, resize(WindowEdge.BottomRight, 40, 5).second)
        assertEquals(600.dp, resize(WindowEdge.BottomRight, 5, 20).second)
        assertEquals(DpOffset(140.dp, 100.dp) to 620.dp, resize(WindowEdge.BottomLeft, -60, 10))
    }

    @Test
    fun `non si stringe sotto il minimo né esce dalla schermata`() {
        assertEquals(400.dp, resize(WindowEdge.Right, -500).second)
        assertEquals(DpOffset(360.dp, 100.dp) to 400.dp, resize(WindowEdge.Left, 500))
        // A destra c'è posto fino al bordo della schermata, a sinistra fino a x = 0.
        assertEquals(1200.dp, resize(WindowEdge.Right, 5000).second)
        assertEquals(DpOffset(0.dp, 100.dp) to 760.dp, resize(WindowEdge.Left, -5000))
    }
}
