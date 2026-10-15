package departureboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class SplitFlapTextTest {

    private val text = mutableStateOf("")

    private val scene = ImageComposeScene(width = 200, height = 40, density = Density(1f)) {
        CompositionLocalProvider(LocalDotPitch provides 3.dp) {
            SplitFlapText(text.value, chars = 6)
        }
    }

    /** Fa avanzare la scena di [millis] a frame da 16 ms e ritorna l'ultimo fotogramma in PNG. */
    private var nanos = 0L
    private fun advance(millis: Long): ByteArray {
        val end = nanos + millis * 1_000_000
        var image = scene.render(nanos)
        while (nanos < end) {
            nanos += 16_000_000
            image = scene.render(nanos)
        }
        return image.encodeToData()!!.bytes
    }

    @Test
    fun `senza suono le palette girano fino al testo nuovo`() {
        val blank = advance(100)
        text.value = "ROMA"
        val turning = advance(200)
        val settled = advance(3_000)
        val again = advance(500)

        assertFalse(blank.contentEquals(turning), "dopo 200 ms le palette dovrebbero girare")
        assertFalse(blank.contentEquals(settled), "a fine girata deve comparire il testo")
        assertContentEquals(settled, again, "a fine girata le palette restano ferme")
        scene.close()
    }
}
