package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.tradepro.RiskSimulationInput
import com.foxtrader.app.domain.model.tradepro.RiskSimulationResult

@Immutable
data class RiskSimulatorUiState(
    val input: RiskSimulationInput = RiskSimulationInput.DEFAULT,
    val result: RiskSimulationResult? = null,
    val isRunning: Boolean = false,
    val error: String? = null,
) {
    val hasResult: Boolean get() = result != null && result.runsSimulated > 0
}
