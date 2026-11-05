package com.thehero17.carfridge.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogKind { INFO, TX, RX, ERROR }

data class LogLine(val time: Long, val kind: LogKind, val text: String) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))
        val tag = when (kind) {
            LogKind.INFO -> "   "
            LogKind.TX -> ">> "
            LogKind.RX -> "<< "
            LogKind.ERROR -> "!! "
        }
        return "$ts $tag$text"
    }
}

/** In-memory log of everything that happens on the BLE link; shareable for reverse engineering. */
class LogBuffer(private val capacity: Int = 3000) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    fun add(kind: LogKind, text: String) {
        val line = LogLine(System.currentTimeMillis(), kind, text)
        android.util.Log.d("CarFridge", line.format())
        _lines.update { (it + line).takeLast(capacity) }
    }

    fun info(text: String) = add(LogKind.INFO, text)
    fun error(text: String) = add(LogKind.ERROR, text)
    fun clear() { _lines.value = emptyList() }
    fun export(): String = _lines.value.joinToString("\n") { it.format() }
}
