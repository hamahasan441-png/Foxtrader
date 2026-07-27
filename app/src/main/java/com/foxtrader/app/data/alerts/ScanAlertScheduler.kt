package com.foxtrader.app.data.alerts

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns WorkManager scheduling for periodic AI scan alerts.
 *
 * Keeping this outside [com.foxtrader.app.FoxTraderApp] makes the background
 * policy testable/configurable and lets Settings immediately apply the user's
 * enabled/interval choices without restarting the app.
 */
@Singleton
class ScanAlertScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private var started = false

    /** Begin observing persisted scheduling preferences. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true

        combine(
            appPreferences.backgroundScanEnabled,
            appPreferences.backgroundScanIntervalMinutes,
        ) { enabled, intervalMinutes ->
            ScheduleConfig(enabled = enabled, intervalMinutes = intervalMinutes)
        }
            .distinctUntilChanged()
            .onEach { apply(it.enabled, it.intervalMinutes) }
            .launchIn(scope)
    }

    /** Apply a schedule immediately (used by Settings Save and preference observer). */
    fun apply(enabled: Boolean, intervalMinutes: Int) {
        if (enabled) {
            schedule(intervalMinutes)
        } else {
            cancel()
        }
    }

    fun schedule(intervalMinutes: Int) {
        val interval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<ScanAlertWorker>(
            repeatInterval = interval.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        )
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ScanAlertWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(ScanAlertWorker.WORK_NAME)
    }

    private data class ScheduleConfig(
        val enabled: Boolean,
        val intervalMinutes: Int,
    )

    companion object {
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15
        const val WORK_TAG = "fox_scan_alerts"
    }
}
