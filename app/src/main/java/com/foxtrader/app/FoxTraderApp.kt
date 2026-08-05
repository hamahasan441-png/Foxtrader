package com.foxtrader.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.foxtrader.app.data.alerts.ScanAlertScheduler
import com.foxtrader.app.data.crash.CrashReporter
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

    @Inject
    lateinit var crashReporter: CrashReporter

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Install the crash handler first so early-startup failures are captured
        // (only when the user has opted in; the handler re-checks the flag).
        // Wrapped in try-catch so a crash reporter failure never blocks app start.
        try {
            crashReporter.install()
        } catch (e: Exception) {
            Log.w("FoxTraderApp", "Crash reporter install failed", e)
        }
        aiDecisionConfigSynchronizer.start()
        riskAlertConfigSynchronizer.start()
        scanAlertScheduler.start()
    }
}
