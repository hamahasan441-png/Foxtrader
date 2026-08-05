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
     * Report a caught/non-fatal exception for diagnostics.
     */
    fun report(throwable: Throwable)
}
