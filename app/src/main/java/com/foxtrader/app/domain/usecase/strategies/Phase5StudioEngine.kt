package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.SignalManagerPolicy
import com.foxtrader.app.domain.model.SignalVisibility
import javax.inject.Inject
import javax.inject.Singleton

/** Phase 5 display gate. It never creates a signal; it only filters already-computed signals. */
@Singleton
class Phase5StudioEngine @Inject constructor() {
    fun filterSignals(signals: List<ChartSignal>, policy: SignalManagerPolicy): List<ChartSignal> {
        val safe = policy.sanitized()
        return signals.asSequence()
            .filter { it.confidence.coerceIn(0.0, 100.0) >= safe.minConfidence.toDouble() }
            .filter { signal ->
                when (safe.visibility) {
                    SignalVisibility.LIVE_ONLY -> signal.isLive
                    SignalVisibility.CONFIRMED_HISTORY -> !signal.isLive
                    SignalVisibility.ALL_RESEARCH -> true
                }
            }
            .filter { signal -> !safe.requireConfirmedBar || signal.isLive || safe.visibility != SignalVisibility.ALL_RESEARCH }
            .sortedByDescending { it.timestamp }
            .take(safe.maxVisibleSignals)
            .toList()
    }
}
