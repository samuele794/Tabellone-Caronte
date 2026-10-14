package debuglog

/** Nel browser `println` finisce nella console. */
internal actual fun platformLog(tag: String, message: String) {
    message.lines().forEach { println("[$tag] $it") }
}
