package com.foxtrader.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.foxtrader.app.data.crash.CrashReporter
import com.foxtrader.app.data.local.CandleRetentionScheduler
import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.domain.usecase.ai.AiDecisionConfigSynchronizer
import com.foxtrader.app.domain.usecase.preferences.RiskAlertConfigSynchronizer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** FoxTrader application entry point and WorkManager configuration. */
@HiltAndroidApp
class FoxTraderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var candleRetentionScheduler: CandleRetentionScheduler

    @Inject
    lateinit var aiDecisionConfigSynchronizer: AiDecisionConfigSynchronizer

    @Inject
    lateinit var riskAlertConfigSynchronizer: RiskAlertConfigSynchronizer

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var dukascopyDataSource: DukascopyDataSource

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        aiDecisionConfigSynchronizer.start()
        riskAlertConfigSynchronizer.start()
        candleRetentionScheduler.start()
    }

    /**
     * Release retained market-data buckets when the system asks for memory.
     *
     * `MEMORY` The provider caches exist purely to avoid re-downloading
     * completed history; every entry is reproducible from the network. Holding
     * them while the platform is trying to reclaim memory is what turns a
     * background trim into a foreground `OutOfMemoryError` the next time the
     * chart allocates.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) dukascopyDataSource.clearCaches()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        dukascopyDataSource.clearCaches()
    }
}
