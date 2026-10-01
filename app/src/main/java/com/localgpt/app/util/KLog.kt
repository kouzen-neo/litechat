package com.localgpt.app.util

import android.content.Context
import android.util.Log
import com.localgpt.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat wrapper that is safe to call from code paths also executed by JVM unit
 * tests: android.util.Log is a stub there and throws "Method ... not mocked",
 * which would crash a passing test. Falls back to stderr so the message is
 * still visible when the code runs on the JVM (unit tests).
 *
 * Every message is appended to an in-app ring buffer ([entries]).
 *
 * Release builds ([BuildConfig.DEBUG] == false) never write to logcat and never
 * persist logs to disk — the in-memory buffer keeps the in-app Logs screen
 * working without leaking potentially sensitive text (e.g. user queries) to
 * persistent storage. Debug builds additionally persist to [logFile] so logs
 * survive crashes, force closes, and process restarts.
 */
object KLog {
    const val MAX_BUFFER = 500
    private const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024L // 2 MB

    /** One buffered log line with the level needed for UI coloring. */
    data class LogEntry(
        val text: String,
        val isError: Boolean,
        val isWarning: Boolean,
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Initializes disk persistence from application context.
     * Loads previous session logs from disk into the in-memory buffer.
     *
     * No-op on release builds: logs are never persisted to disk there.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        try {
            val logsDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
            val file = File(logsDir, "app_logs.txt")
            logFile = file

            if (file.exists() && file.length() > 0) {
                val previousLines = file.readLines().takeLast(MAX_BUFFER)
                val initialEntries =
                    previousLines.map { line ->
                        val isErr = line.contains("[ERROR]") || line.contains("FATAL") || line.contains("Exception")
                        val isWarn = line.contains("[WARN]")
                        LogEntry(line, isError = isErr, isWarning = isWarn)
                    }
                _entries.value = initialEntries
            }
        } catch (_: Throwable) {
        }
    }

    /** Drop all buffered entries and truncate disk log file. */
    fun clear() {
        _entries.value = emptyList()
        logScope.launch {
            try {
                logFile?.writeText("")
            } catch (_: Throwable) {
            }
        }
    }

    fun d(
        tag: String,
        msg: String,
    ) = log(Log.DEBUG, tag, msg, isError = false, isWarning = false)

    fun w(
        tag: String,
        msg: String,
    ) = log(Log.WARN, tag, msg, isError = false, isWarning = true)

    fun e(
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    ) {
        val fullMsg =
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                "$msg\n$sw"
            } else {
                msg
            }
        log(Log.ERROR, tag, fullMsg, isError = true, isWarning = false)
    }

    private fun log(
        level: Int,
        tag: String,
        msg: String,
        isError: Boolean,
        isWarning: Boolean,
    ) {
        // Release builds: never write to logcat (may carry sensitive text).
        if (BuildConfig.DEBUG) {
            try {
                when (level) {
                    Log.ERROR -> Log.e(tag, msg)
                    Log.WARN -> Log.w(tag, msg)
                    else -> Log.d(tag, msg)
                }
            } catch (_: Throwable) {
                // JVM unit test environment: android.util.Log throws "not mocked".
                System.err.println("[$tag] $msg")
            }
        }

        // In-memory ring buffer is always kept so the in-app Logs screen works.
        _entries.update { (it + LogEntry(msg, isError, isWarning)).takeLast(MAX_BUFFER) }

        // Release builds: never persist logs to disk.
        if (!BuildConfig.DEBUG) return

        // Persist to disk asynchronously
        logFile?.let { file ->
            val levelTag =
                when {
                    isError -> "[ERROR]"
                    isWarning -> "[WARN]"
                    else -> "[DEBUG]"
                }
            logScope.launch {
                try {
                    synchronized(this@KLog) {
                        if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                            // Truncate older half if log file exceeds limit
                            val lines = file.readLines()
                            val kept = lines.takeLast(MAX_BUFFER)
                            file.writeText(kept.joinToString("\n") + "\n")
                        }
                        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
                        FileWriter(file, true).use { writer ->
                            writer.write("$timestamp $levelTag [$tag] $msg\n")
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }
}
