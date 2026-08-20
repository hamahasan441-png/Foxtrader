package com.foxtrader.app.data.crash

import android.os.Handler
import android.os.Looper
import com.foxtrader.app.BuildConfig
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote crash reporter that delegates to a [RemoteCrashBackend].
 *
 * Key privacy guarantees:
 * - All exception data is sanitized before transmission: only the exception type,
 *   stack frames, and explicitly-provided non-PII context keys are sent.
 *   Exception messages are intentionally stripped because they can contain
 *   runtime values (symbols, prices, user notes).
 * - No data leaves the device unless [AppPreferences.crashReportingEnabled] is true.
 * - Includes ANR detection via [AnrWatchdog] that monitors main-thread responsiveness.
 *
 * The DSN/project key is read from [BuildConfig.CRASH_REPORTING_DSN]. When blank
 * (the default until a real remote SDK is configured), the backend initializes
 * as a no-op.
 */
@Singleton
class RemoteCrashReporter @Inject constructor(
    private val appPreferences: AppPreferences,
    private val backend: RemoteCrashBackend,
    private val anrWatchdog: AnrWatchdog,
) : CrashReporter {

    /**
     * Provides the main-thread [Handler] for the ANR watchdog. Extracted for
     * testability (unit tests cannot access [Looper.getMainLooper]).
     */
    internal var mainHandlerProvider: () -> Handler = { Handler(Looper.getMainLooper()) }

    override fun install() {
        val dsn = BuildConfig.CRASH_REPORTING_DSN
        backend.initialize(dsn)
        backend.setEnabled(appPreferences.crashReportingEnabled.value)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (appPreferences.crashReportingEnabled.value) {
                    val sanitized = sanitize(throwable, emptyMap())
                    backend.captureException(sanitized)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }

        if (appPreferences.crashReportingEnabled.value) {
            startAnrWatchdog()
        }
    }

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        if (!appPreferences.crashReportingEnabled.value) return
        val sanitized = sanitize(throwable, context)
        backend.captureException(sanitized)
    }

    override fun setEnabled(enabled: Boolean) {
        backend.setEnabled(enabled)
        if (enabled) {
            startAnrWatchdog()
        } else {
            anrWatchdog.stop()
        }
    }

    override fun recordBreadcrumb(message: String, category: String) {
        if (!appPreferences.crashReportingEnabled.value) return
        backend.addBreadcrumb(message, category)
    }

    private fun startAnrWatchdog() {
        val mainHandler = mainHandlerProvider()
        anrWatchdog.start(mainHandler, this)
    }

    /**
     * Strips PII from a throwable chain. Only the exception type name and
     * stack frames are preserved. Exception messages are intentionally excluded.
     */
    internal fun sanitize(
        throwable: Throwable,
        context: Map<String, String>,
        depth: Int = 0,
    ): SanitizedException {
        val frames = throwable.stackTrace.take(MAX_FRAMES).map { element ->
            StackFrame(
                className = element.className,
                methodName = element.methodName,
                fileName = element.fileName,
                lineNumber = element.lineNumber,
            )
        }

        val causedBy = throwable.cause
            ?.takeIf { depth < MAX_CAUSE_DEPTH }
            ?.let { sanitize(it, emptyMap(), depth + 1) }

        return SanitizedException(
            type = throwable.javaClass.name,
            frames = frames,
            context = context,
            causedBy = causedBy,
        )
    }

    private companion object {
        const val MAX_FRAMES = 40
        const val MAX_CAUSE_DEPTH = 5
    }
}
