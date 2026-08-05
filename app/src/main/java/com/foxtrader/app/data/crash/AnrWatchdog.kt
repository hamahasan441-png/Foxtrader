package com.foxtrader.app.data.crash

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ANR (Application Not Responding) watchdog.
 *
 * A daemon thread that posts a no-op callback to the main-thread [Handler] every
 * [CHECK_INTERVAL_MS] milliseconds. If the callback does not execute within
 * [TIMEOUT_MS], the main thread is considered unresponsive and a thread dump is
 * captured and reported via the provided [CrashReporter].
 *
 * The watchdog respects the crash-reporting opt-in: it only reports when enabled,
 * and stops monitoring when the reporter is disabled.
 */
@Singleton
class AnrWatchdog @Inject constructor() {

    @Volatile
    private var running = false

    private var watchdogThread: Thread? = null

    /**
     * Starts the ANR detection loop. Safe to call multiple times; subsequent
     * calls are ignored if the watchdog is already running.
     *
     * @param mainHandler the handler bound to the main looper
     * @param reporter the crash reporter to receive ANR events
     */
    fun start(mainHandler: Handler, reporter: CrashReporter) {
        if (running) return
        running = true

        val thread = Thread({
            while (running) {
                val latch = CountDownLatch(1)
                mainHandler.post { latch.countDown() }

                val responded = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!responded && running) {
                    val mainThread = Looper.getMainLooper().thread
                    val threadDump = buildThreadDump(mainThread)
                    reporter.recordException(
                        AnrException(threadDump),
                        mapOf("anr" to "true", "dump_length" to threadDump.length.toString()),
                    )
                }

                if (running) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                }
            }
        }, "AnrWatchdog")
        thread.isDaemon = true
        thread.start()
        watchdogThread = thread
    }

    /** Stops the watchdog loop. The thread terminates on its next cycle. */
    fun stop() {
        running = false
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun buildThreadDump(thread: Thread): String = buildString {
        appendLine("ANR detected -- main thread stack trace:")
        thread.stackTrace.forEach { frame ->
            appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 5_000L
    }
}

/**
 * Marker exception type for ANR events. The [message] contains the main-thread
 * stack dump at the time the ANR was detected.
 */
class AnrException(message: String) : RuntimeException(message)
