package com.foxtrader.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.foxtrader.app.data.crash.CrashReporter
import com.foxtrader.app.data.local.CandleRetentionScheduler
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
}
