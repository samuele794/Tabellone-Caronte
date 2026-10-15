package departureboard

import kotlin.test.Test
import kotlin.test.assertEquals

class FlapPagesTest {

    @Test
    fun `un testo che entra resta una pagina sola`() {
        assertEquals(listOf("BARI C.LE"), flapPages("Bari C.le", 14))
        assertEquals(listOf(""), flapPages("", 14))
    }

    @Test
    fun `va a capo tra le parole`() {
        assertEquals(
            listOf("FERMA A:", "BOLOGNA C.LE -", "ANCONA"),
            flapPages("FERMA A: BOLOGNA C.LE - ANCONA", 14),
        )
    }

    @Test
    fun `nessuna pagina supera la colonna`() {
        val text =
            "FERMA A: BOLOGNA C.LE (9:30) - ANCONA (11:20) - PESCARA C.LE (12:40) - BARI C.LE"
        val pages = flapPages(text, 28)
        pages.forEach { assert(it.length <= 28) { "'$it' è più lunga di 28" } }
        assertEquals(DotMatrixFont.normalize(text), pages.joinToString(" "))
    }

    @Test
    fun `una parola più lunga della colonna si spezza`() {
        assertEquals(listOf("CASTIG", "L.FIOR", "ENT."), flapPages("CASTIGL.FIORENT.", 6))
    }

    @Test
    fun `gli spazi ripetuti non fanno pagine vuote`() {
        assertEquals(
            listOf("AVVISO", "UNO", "AVVISO", "DUE"),
            flapPages("AVVISO UNO   AVVISO DUE", 7)
        )
    }

    @Test
    fun `accenti come sul tabellone`() {
        assertEquals(listOf("CITTA'"), flapPages("Città", 10))
    }
}
