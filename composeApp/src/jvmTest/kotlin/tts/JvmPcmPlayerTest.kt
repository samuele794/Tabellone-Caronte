package tts

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPcmPlayerTest {

    private fun ShortArray.decode(bytes: ByteArray) =
        List(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }

    @Test
    fun `a guadagno pieno i campioni restano quelli`() {
        val samples = shortArrayOf(0, 1, -1, 12_345, Short.MAX_VALUE, Short.MIN_VALUE)
        assertEquals(samples.toList(), samples.decode(pcmBytes(samples, 0, samples.size, 1f, 1f)))
    }

    @Test
    fun `un cambio di guadagno scende in rampa dentro il pezzo`() {
        val samples = ShortArray(4) { 10_000 }
        val out = samples.decode(pcmBytes(samples, 0, 4, 1f, 0.2f))
        assertEquals(listOf<Short>(10_000, 8_000, 6_000, 4_000), out)
    }

    @Test
    fun `si converte solo il pezzo chiesto`() {
        val samples = shortArrayOf(1, 2, 3, 4, 5)
        assertEquals(listOf<Short>(2, 3), samples.decode(pcmBytes(samples, 1, 3, 1f, 1f)))
    }
}
