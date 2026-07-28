package com.foxtrader.app.data.crash

import android.content.Context
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline, privacy-preserving crash reporter.
 *
 * When (and only when) the user has opted in, an uncaught exception is recorded
 * to a small rotating set of files in the app's private storage. This is enough
 * for the user to share a diagnostic with support, with **no** network transmission
 * and **no** personal data:
 *
 * - Only the exception class names and the code stack frames (class/method/line)
 *   are written. Exception *messages* are intentionally omitted because they can
 *   contain runtime values (symbols, prices, notes) that qualify as user data.
 * - Nothing is uploaded anywhere. The files live in `filesDir/crash_logs/`.
 *
 * The original default handler is always chained so the OS still shows its crash
 * dialog and the process terminates normally.
 */
@Singleton
class LocalCrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
) : CrashReporter {

    override fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (appPreferences.crashReportingEnabled.value) {
                    writeReport(thread, throwable)
                }
            }
            // Always delegate to the OS/previous handler so behavior is unchanged.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
        rotate(dir)

        val timestamp = SimpleDateFormat(FILE_TIMESTAMP, Locale.US).format(Date())
        val report = buildReport(thread, throwable)
        File(dir, "crash_$timestamp.txt").writeText(report)
    }

    /**
     * Builds a no-PII diagnostic: exception type chain and stack frames only.
     * Exception messages and any `cause` messages are deliberately excluded.
     */
    private fun buildReport(thread: Thread, throwable: Throwable): String = buildString {
        appendLine("FoxTrader crash diagnostic")
        appendLine("time=${SimpleDateFormat(HUMAN_TIMESTAMP, Locale.US).format(Date())}")
        appendLine("thread=${thread.name}")
        appendLine()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth <= MAX_CAUSE_DEPTH) {
            appendLine(if (depth == 0) "exception: ${current.javaClass.name}" else "caused by: ${current.javaClass.name}")
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
            }
            current = current.cause
            depth++
        }
    }

    /** Keeps at most [MAX_FILES] crash files, deleting the oldest first. */
    private fun rotate(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("crash_") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val excess = files.size - (MAX_FILES - 1)
        if (excess > 0) files.take(excess).forEach { it.delete() }
    }

    private companion object {
        const val CRASH_DIR = "crash_logs"
        const val MAX_FILES = 5
        const val MAX_FRAMES = 40
        const val MAX_CAUSE_DEPTH = 5
        const val FILE_TIMESTAMP = "yyyyMMdd_HHmmss"
        const val HUMAN_TIMESTAMP = "yyyy-MM-dd HH:mm:ss"
    }
}
