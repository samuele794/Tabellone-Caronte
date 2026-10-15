package com.takaotech.departureboard.debuglog

/**
 * Log di debug del tabellone e degli annunci: logcat su Android (`adb logcat -s Tabellone Annunci`),
 * standard output su desktop. Il messaggio si costruisce solo se il log è attivo.
 */
object DebugLog {
    var enabled = true

    fun d(tag: String, message: () -> String) {
        if (enabled) platformLog(tag, message())
    }
}

internal expect fun platformLog(tag: String, message: String)
