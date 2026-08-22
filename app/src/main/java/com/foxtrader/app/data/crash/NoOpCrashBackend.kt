package com.foxtrader.app.data.crash

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [RemoteCrashBackend] when no remote SDK is configured.
 *
 * All operations are logged locally via [Log] at debug level. This backend ships
 * until the actual Sentry/Crashlytics dependency is added to the build, at which
 * point a real implementation replaces this binding in [CrashModule][com.foxtrader.app.di.CrashModule].
 */
@Singleton
class NoOpCrashBackend @Inject constructor() : RemoteCrashBackend {

    override fun initialize(dsn: String) {
        Log.d(TAG, "initialize(dsn=<redacted>) -- no remote SDK configured")
    }

    override fun captureException(sanitized: SanitizedException) {
        Log.d(TAG, "captureException(type=${sanitized.type}, frames=${sanitized.frames.size})")
    }

    override fun captureAnr(threadDump: String) {
        Log.d(TAG, "captureAnr(dumpLength=${threadDump.length})")
    }

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "setEnabled($enabled)")
    }

    override fun addBreadcrumb(message: String, category: String) {
        Log.d(TAG, "addBreadcrumb(category=$category, messageLength=${message.length})")
    }

    private companion object {
        const val TAG = "NoOpCrashBackend"
    }
}
