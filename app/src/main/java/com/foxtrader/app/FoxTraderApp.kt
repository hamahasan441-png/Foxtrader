package com.foxtrader.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxtrader.app.data.alerts.ScanAlertWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * FoxTrader application entry point.
 * [HiltAndroidApp] triggers Hilt's code generation and creates the
 * application-level dependency container.
 *
 * Also configures WorkManager with the Hilt worker factory and schedules
 * the periodic background scan-alert worker.
 */
@HiltAndroidApp
class FoxTraderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleScanAlertWorker()
    }

    /**
     * Schedule the periodic background scan that evaluates watchlist symbols
     * through the AI pipeline and fires push alerts for approved setups.
     * Runs every 15 minutes (WorkManager minimum) with KEEP policy so it
     * doesn't restart if already enqueued.
     */
    private fun scheduleScanAlertWorker() {
        val request = PeriodicWorkRequestBuilder<ScanAlertWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ScanAlertWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
