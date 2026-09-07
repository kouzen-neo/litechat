package com.localgpt.app

import android.app.Application
import com.localgpt.app.util.KLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        KLog.init(this)
        installCrashReporter()
    }

    /**
     * Writes any uncaught exception to an external-files report so it survives
     * the process death and can be shown in-app on next launch (no adb needed).
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report =
                    buildString {
                        appendLine("Thread: ${thread.name}")
                        appendLine("Time: ${System.currentTimeMillis()}")
                        appendLine(sw)
                    }
                File(dir, "crash_report.txt").writeText(report)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashReportFile(context: android.content.Context): File? {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            return File(dir, "crash_report.txt").takeIf { it.exists() && it.length() > 0 }
        }

        fun clearCrashReport(context: android.content.Context) {
            try {
                crashReportFile(context)?.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
