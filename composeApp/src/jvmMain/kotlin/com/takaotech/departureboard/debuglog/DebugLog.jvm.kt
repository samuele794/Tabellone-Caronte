package com.takaotech.departureboard.debuglog

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal actual fun platformLog(tag: String, message: String) {
    val time = LocalTime.now().format(Time)
    message.lines().forEach { println("$time [$tag] $it") }
}
