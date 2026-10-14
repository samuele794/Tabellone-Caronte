package departureboard

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoScrollTest {

    @Test
    fun `avanza solo a punti interi`() {
        // Punto di 2,5 px: meno di un punto resta fermo, 1 punto è 2,5 → 3 px, 2 punti 5 px.
        assertEquals(0, dotPosition(start = 0, target = 100, dotPx = 2.5f, travelledPx = 2.2f))
        assertEquals(3, dotPosition(start = 0, target = 100, dotPx = 2.5f, travelledPx = 2.5f))
        assertEquals(5, dotPosition(start = 0, target = 100, dotPx = 2.5f, travelledPx = 7.4f))
    }

    @Test
    fun `l'ultimo passo si ferma sul bersaglio`() {
        assertEquals(10, dotPosition(start = 0, target = 10, dotPx = 4f, travelledPx = 12f))
        assertEquals(10, dotPosition(start = 0, target = 10, dotPx = 4f, travelledPx = 1_000f))
    }

    @Test
    fun `torna indietro verso la cima`() {
        assertEquals(92, dotPosition(start = 100, target = 0, dotPx = 4f, travelledPx = 9f))
        assertEquals(0, dotPosition(start = 100, target = 0, dotPx = 4f, travelledPx = 101f))
    }

    @Test
    fun `parte da una posizione qualsiasi dopo uno scroll a mano`() {
        assertEquals(41, dotPosition(start = 37, target = 100, dotPx = 4f, travelledPx = 4f))
    }
}
