package debuglog

import android.util.Log

/** Una riga per voce: logcat tronca i messaggi oltre ~4000 caratteri. */
internal actual fun platformLog(tag: String, message: String) {
    message.lines().forEach { Log.d(tag, it) }
}
