package com.foxtrader.app.data.alerts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary compatibility shim while Settings is migrated off the removed
 * background scanner. It schedules no work and will be deleted in this PR.
 */
@Deprecated("Background scanner removed")
@Singleton
class ScanAlertScheduler @Inject constructor() {
    fun start() = Unit
    fun apply(enabled: Boolean, intervalMinutes: Int) = Unit

    companion object {
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15
    }
}
