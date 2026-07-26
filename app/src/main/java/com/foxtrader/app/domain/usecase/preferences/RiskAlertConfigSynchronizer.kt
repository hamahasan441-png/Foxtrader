package com.foxtrader.app.domain.usecase.preferences

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.alerts.AlertEngine
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps persisted risk/alert settings applied to domain singletons everywhere.
 *
 * Settings writes to [AppPreferences]; this synchronizer makes the app-wide
 * [RiskEngine] and [AlertEngine] consume those values on launch and after every
 * preference update.
 */
@Singleton
class RiskAlertConfigSynchronizer @Inject constructor(
    private val appPreferences: AppPreferences,
    private val riskEngine: RiskEngine,
    private val alertEngine: AlertEngine,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var started = false

    fun start() {
        if (started) return
        started = true

        combine(
            appPreferences.riskConfig,
            appPreferences.alertConfig,
        ) { risk, alert -> RuntimeConfig(risk, alert) }
            .distinctUntilChanged()
            .onEach { config ->
                riskEngine.updateConfig(config.riskConfig)
                alertEngine.updateConfig(config.alertConfig)
            }
            .launchIn(scope)
    }

    private data class RuntimeConfig(
        val riskConfig: RiskConfig,
        val alertConfig: AlertConfig,
    )
}
