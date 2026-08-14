package com.foxtrader.app.data.local

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the cache-retention safety net independently of market refreshes. */
@Singleton
class CandleRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private var started = false

    /** Idempotently install the periodic retention job. */
    fun start() {
        if (started) return
        started = true
        val request = PeriodicWorkRequestBuilder<CandleRetentionWorker>(
            RETENTION_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(CandleRetentionWorker.WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            CandleRetentionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val RETENTION_INTERVAL_HOURS = 6L
    }
}
