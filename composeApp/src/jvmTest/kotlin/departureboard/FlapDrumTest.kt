package departureboard

import kotlin.test.Test
import kotlin.test.assertEquals

class FlapDrumTest {

    @Test
    fun `lo stesso carattere non gira`() {
        assertEquals(0, FlapDrum.steps('A', 'A'))
    }

    @Test
    fun `gira solo in avanti passando per i caratteri in mezzo`() {
        assertEquals(2, FlapDrum.steps('A', 'C'))
        assertEquals('B', FlapDrum.charAfter('A', 'C', 1))
        assertEquals('C', FlapDrum.charAfter('A', 'C', 2))
        // Da "C" ad "A" fa il giro di tutto il tamburo.
        assertEquals(FlapDrum.SEQUENCE.length - 2, FlapDrum.steps('C', 'A'))
        assertEquals('D', FlapDrum.charAfter('C', 'A', 1))
    }

    @Test
    fun `dalla palette vuota alla lettera`() {
        assertEquals(1, FlapDrum.steps(' ', 'A'))
        assertEquals(26, FlapDrum.steps(' ', 'Z'))
    }

    @Test
    fun `tra due cifre gira il tamburo delle cifre`() {
        assertEquals(1, FlapDrum.steps('9', '0'))
        assertEquals(3, FlapDrum.steps('7', '0'))
        assertEquals('8', FlapDrum.charAfter('7', '0', 1))
        assertEquals('0', FlapDrum.charAfter('7', '0', 3))
    }

    @Test
    fun `un carattere fuori dal tamburo si sostituisce con un giro`() {
        assertEquals(1, FlapDrum.steps('A', '@'))
        assertEquals(1, FlapDrum.steps('@', 'A'))
        assertEquals('@', FlapDrum.charAfter('A', '@', 1))
    }

    @Test
    fun `prima di girare e a fine corsa`() {
        assertEquals('A', FlapDrum.charAfter('A', 'C', 0))
        assertEquals('C', FlapDrum.charAfter('A', 'C', 10))
    }

    @Test
    fun `allineamento sulle palette`() {
        assertEquals("AB   ", alignedLine("AB", 5, DotAlign.Start))
        assertEquals("   AB", alignedLine("AB", 5, DotAlign.End))
        assertEquals(" AB  ", alignedLine("AB", 5, DotAlign.Center))
        assertEquals("ABC", alignedLine("ABCDE", 3, DotAlign.End))
    }
}
