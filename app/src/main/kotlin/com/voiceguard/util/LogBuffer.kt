package com.voiceguard.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of the last 500 log lines, exported from Diagnostics. */
object LogBuffer {
    private const val MAX_LINES = 500
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} $tag: $message"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun export(): String = snapshot().joinToString("\n")
}

