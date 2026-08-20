package com.foxtrader.app.feature.calculator.presentation

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.RiskAwarePositionCalculator

/**
 * Draft state for the position-size calculator.
 *
 * Prices are held as strings so fields can be edited freely (including
 * intermediate states like "1." or ""), and are parsed only when calculating.
 */
data class CalculatorForm(
    val symbol: String = "",
    val direction: Direction = Direction.BULLISH,
    val entryPrice: String = "",
    val stopLoss: String = "",
    val takeProfit: String = "",
    val riskPercent: String = "1.0",
    val accountBalance: String = "",
) {
    val isComplete: Boolean
        get() = symbol.isNotBlank() &&
            entryPrice.toDoubleOrNull() != null &&
            stopLoss.toDoubleOrNull() != null &&
            (riskPercent.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (accountBalance.toDoubleOrNull() ?: 0.0) > 0.0
}

data class CalculatorUiState(
    val form: CalculatorForm = CalculatorForm(),
    val outcome: RiskAwarePositionCalculator.Outcome? = null,
) {
    val sized: RiskAwarePositionCalculator.Outcome.Sized?
        get() = outcome as? RiskAwarePositionCalculator.Outcome.Sized

    val validationErrors: List<String>
        get() = (outcome as? RiskAwarePositionCalculator.Outcome.Invalid)?.reasons.orEmpty()

    /**
     * True when a size was computed but the risk engine would refuse it. The UI
     * must still show the numbers — a trader needs to see *how far over* they
     * are — but it has to be unmistakably marked as blocked.
     */
    val blockedByRisk: Boolean get() = sized?.allowed == false
}
