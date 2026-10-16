package com.takaotech.departureboard.tts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Mp3DurationTest {

    private fun resource(name: String) =
        File("src/commonMain/composeResources/files/$name").readBytes()

    @Test
    fun `il jingle dura poco più di 5 secondi`() {
        // `afinfo` di macOS: 5,224 s.
        val duration = mp3Duration(resource("trenitalia-station-anouncements.mp3"))!!
        assertEquals(
            5_224.milliseconds.inWholeMilliseconds.toDouble(),
            duration.inWholeMilliseconds.toDouble(),
            30.0
        )
    }

    @Test
    fun `il sottofondo dura circa 20 minuti`() {
        // `afinfo` di macOS: 1239,7 s.
        val duration = mp3Duration(resource("milano-central-background.mp3"))!!
        assertEquals(1_239.7, duration.toDouble(kotlin.time.DurationUnit.SECONDS), 0.5)
    }

    @Test
    fun `byte che non sono un MP3 non hanno durata`() {
        assertNull(mp3Duration(ByteArray(0)))
        assertNull(mp3Duration("non è un mp3".encodeToByteArray()))
        assertEquals(null, mp3Duration(ByteArray(4096)))
        // Un solo frame MPEG 1 Layer III a 128 kbps e 44,1 kHz: 1152 campioni.
        val frame = ByteArray(417).apply {
            this[0] = 0xFF.toByte(); this[1] = 0xFB.toByte(); this[2] = 0x90.toByte(); this[3] =
            0x00
        }
        assertEquals(
            (1152 * 1_000_000L / 44_100).toDouble(),
            mp3Duration(frame)!!.inWholeMicroseconds.toDouble(),
            1.0
        )
        assertEquals(true, mp3Duration(frame)!! < 1.seconds)
    }
}
