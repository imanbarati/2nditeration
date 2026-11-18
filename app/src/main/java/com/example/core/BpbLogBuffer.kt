package com.example.core

import com.example.data.model.LogEntry
import com.example.data.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object BpbLogBuffer {
    private const val MAX_LOGS = 400
    private val idGenerator = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(
        listOf(
            LogEntry(
                id = idGenerator.getAndIncrement(),
                timestamp = timeFormat.format(Date()),
                level = LogLevel.INFO,
                tag = "CORE",
                message = "BPB VPN engine initialized. Ready for tunnel or Clean IP scan."
            )
        )
    )
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = idGenerator.getAndIncrement(),
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        val current = _logs.value
        val updated = if (current.size >= MAX_LOGS) {
            current.drop(current.size - MAX_LOGS + 1) + entry
        } else {
            current + entry
        }
        _logs.value = updated
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    @Synchronized
    fun clear() {
        _logs.value = emptyList()
        info("CORE", "Log buffer cleared.")
    }
}
