package com.foxtrader.app.data.crash

/**
 * Crash/ANR reporting seam.
 *
 * Deliberately minimal so the concrete backend can evolve (local file today, an
 * opt-in remote service such as Crashlytics/Sentry later) without touching call
 * sites. The default implementation ([LocalCrashReporter]) is fully offline and
 * privacy-preserving: it records only crash *diagnostics* (exception type and
 * code stack frames), never user data, and only when the user has explicitly
 * opted in via settings.
 */
interface CrashReporter {

    /**
     * Installs the crash handler. Safe to call once at application start. The
     * handler itself re-checks the opt-in flag at crash time, so toggling the
     * setting takes effect without reinstalling.
     */
    fun install()

    /**
     * Records a non-fatal exception with optional context metadata.
     * Implementations must sanitize the throwable (strip messages, keep only
     * type + stack frames) before any remote transmission.
     */
    fun recordException(throwable: Throwable, context: Map<String, String> = emptyMap()) {}

    /**
     * Enables or disables crash reporting at runtime. Implementations should
     * propagate this to any underlying backend so no data leaves the device
     * when the user has opted out.
     */
    fun setEnabled(enabled: Boolean) {}

    /**
     * Records a breadcrumb for crash context. Breadcrumbs are short navigation
     * or state-change markers that help diagnose what led to a crash.
     */
    fun recordBreadcrumb(message: String, category: String = "app") {}
}
