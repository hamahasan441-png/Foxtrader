package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.DecisionConfig
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
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
 * Keeps the singleton AI decision runtime aligned with persisted preferences.
 *
 * Without this bridge, settings would only affect the current Settings screen
 * instance. Starting the synchronizer at app launch makes chart AI, background
 * scan alerts, strategies and backtests share the same deterministic gate.
 */
@Singleton
class AiDecisionConfigSynchronizer @Inject constructor(
    private val appPreferences: AppPreferences,
    private val decisionEngine: MasterDecisionEngine,
    private val aiAlertService: AiAlertService,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var started = false

    fun start() {
        if (started) return
        started = true

        combine(
            appPreferences.aiMinConfluences,
            appPreferences.aiMinConfidence,
            appPreferences.aiAlertCooldownMinutes,
        ) { confluences, confidence, cooldown ->
            AiRuntimeConfig(confluences, confidence, cooldown)
        }
            .distinctUntilChanged()
            .onEach { config ->
                decisionEngine.updateConfig(
                    DecisionConfig(
                        minRequiredConfluences = config.minConfluences,
                        minConfidence = config.minConfidence.toDouble(),
                    )
                )
                aiAlertService.cooldownMs = config.alertCooldownMinutes * 60_000L
            }
            .launchIn(scope)
    }

    private data class AiRuntimeConfig(
        val minConfluences: Int,
        val minConfidence: Int,
        val alertCooldownMinutes: Int,
    )
}
