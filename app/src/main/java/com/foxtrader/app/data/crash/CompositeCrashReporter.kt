package com.foxtrader.app.data.crash

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composite [CrashReporter] that dispatches all operations to both the local
 * file-based reporter and the remote backend reporter.
 *
 * This ensures crash diagnostics are always available locally for the user to
 * share with support, while simultaneously (and only when opted in) sending
 * sanitized data to the remote service for aggregate analysis.
 */
@Singleton
class CompositeCrashReporter @Inject constructor(
    private val localReporter: LocalCrashReporter,
    private val remoteReporter: RemoteCrashReporter,
) : CrashReporter {

    override fun install() {
        localReporter.install()
        remoteReporter.install()
    }

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        localReporter.recordException(throwable, context)
        remoteReporter.recordException(throwable, context)
    }

    override fun setEnabled(enabled: Boolean) {
        localReporter.setEnabled(enabled)
        remoteReporter.setEnabled(enabled)
    }

    override fun recordBreadcrumb(message: String, category: String) {
        localReporter.recordBreadcrumb(message, category)
        remoteReporter.recordBreadcrumb(message, category)
    }
}
