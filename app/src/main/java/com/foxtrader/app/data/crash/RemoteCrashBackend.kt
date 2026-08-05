package com.foxtrader.app.data.crash

/**
 * Abstraction over the actual remote crash-reporting SDK (Sentry, Crashlytics, etc.).
 *
 * This indirection lets us swap the underlying provider without modifying the
 * [RemoteCrashReporter] logic. The default shipping implementation is
 * [NoOpCrashBackend], which simply delegates to the local file logger until
 * a real SDK dependency is added to the build.
 */
interface RemoteCrashBackend {

    /** Initializes the backend with the given DSN/project key. */
    fun initialize(dsn: String)

    /** Sends a sanitized (no-PII) exception to the remote service. */
    fun captureException(sanitized: SanitizedException)

    /** Sends an ANR report containing the main-thread dump. */
    fun captureAnr(threadDump: String)

    /** Enables or disables the backend at runtime. */
    fun setEnabled(enabled: Boolean)

    /** Attaches a breadcrumb for crash context. */
    fun addBreadcrumb(message: String, category: String)
}

/**
 * A sanitized exception stripped of PII. Contains only the exception type,
 * stack frames (class/method/file/line), and non-PII context keys.
 */
data class SanitizedException(
    val type: String,
    val frames: List<StackFrame>,
    val context: Map<String, String>,
    val causedBy: SanitizedException? = null,
)

/**
 * A single stack frame in a sanitized exception report.
 */
data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
)
