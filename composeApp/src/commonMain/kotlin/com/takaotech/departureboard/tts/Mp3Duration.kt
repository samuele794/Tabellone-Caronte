package com.takaotech.departureboard.tts

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Durata di un MP3 (MPEG 1, 2 o 2.5, Layer III), contando i frame uno dopo l'altro: vale anche
 * per i file a bitrate variabile. Salta il tag ID3v2 iniziale e i byte che non sono un frame;
 * null se non trova nessun frame.
 */
fun mp3Duration(bytes: ByteArray): Duration? {
    fun u8(at: Int) = bytes[at].toInt() and 0xFF

    var at = 0
    if (bytes.size >= 10 && bytes.decodeToString(0, 3) == "ID3") {
        // Dimensione "syncsafe": 7 bit per byte; con il bit 4 dei flag c'è anche un piè di pagina.
        val size = (u8(6) shl 21) or (u8(7) shl 14) or (u8(8) shl 7) or u8(9)
        at = 10 + size + if (u8(5) and 0x10 != 0) 10 else 0
    }
    var samples = 0L
    var sampleRate = 0
    while (at + 4 <= bytes.size) {
        val header = (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
        // 0 = MPEG 2.5, 1 = riservato, 2 = MPEG 2, 3 = MPEG 1.
        val version = (header ushr 19) and 3
        val layer = (header ushr 17) and 3
        val bitrateIndex = (header ushr 12) and 0xF
        val rateIndex = (header ushr 10) and 3
        val valid = header ushr 21 == 0x7FF && version != 1 && layer == 1 &&
                bitrateIndex in 1..14 && rateIndex != 3
        if (!valid) {
            at++
            continue
        }
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) MPEG1_KBPS else MPEG2_KBPS)[bitrateIndex] * 1000
        val rate = SAMPLE_RATES[rateIndex] shr if (mpeg1) 0 else if (version == 2) 1 else 2
        val frameSamples = if (mpeg1) 1152 else 576
        samples += frameSamples
        sampleRate = rate
        at += frameSamples / 8 * bitrate / rate + ((header ushr 9) and 1)
    }
    return if (sampleRate == 0) null else (samples * 1_000_000 / sampleRate).microseconds
}

/** Bitrate dei frame Layer III, in kbps, per indice dell'intestazione. */
private val MPEG1_KBPS =
    intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
private val MPEG2_KBPS =
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

/** Frequenze di campionamento di MPEG 1; MPEG 2 le dimezza, MPEG 2.5 le divide per quattro. */
private val SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)
