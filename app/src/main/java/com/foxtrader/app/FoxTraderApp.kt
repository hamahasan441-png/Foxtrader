package com.foxtrader.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.foxtrader.app.data.alerts.ScanAlertScheduler
import com.foxtrader.app.domain.usecase.ai.AiDecisionConfigSynchronizer
import com.foxtrader.app.domain.usecase.preferences.RiskAlertConfigSynchronizer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * FoxTrader application entry point.
 * [HiltAndroidApp] triggers Hilt's code generation and creates the
 * application-level dependency container.
 *
 * Also configures WorkManager with the Hilt worker factory and delegates
 * periodic scan-alert scheduling to [ScanAlertScheduler].
 */
@HiltAndroidApp
class FoxTraderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var scanAlertScheduler: ScanAlertScheduler

    @Inject
    lateinit var aiDecisionConfigSynchronizer: AiDecisionConfigSynchronizer

    @Inject
    lateinit var riskAlertConfigSynchronizer: RiskAlertConfigSynchronizer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        aiDecisionConfigSynchronizer.start()
        riskAlertConfigSynchronizer.start()
        scanAlertScheduler.start()
    }
}
