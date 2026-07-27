package com.foxtrader.app.feature.calculator.presentation

import androidx.lifecycle.ViewModel
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.RiskAwarePositionCalculator
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Position-size calculator ViewModel.
 *
 * Activates [com.foxtrader.app.domain.usecase.calculator.PositionCalculator],
 * which shipped with zero call sites, and binds it to the risk engine so the
 * suggested size is one the order path would actually accept.
 */
@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val calculator: RiskAwarePositionCalculator,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    /**
     * Seed the form from the live chart so the trader is not retyping context
     * the app already has. Defaults come from the persisted risk config, which
     * keeps the calculator consistent with the sizing the engine would apply.
     */
    fun prefill(symbol: String, lastPrice: Double?) {
        val config = appPreferences.riskConfig.value
        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    symbol = symbol,
                    entryPrice = lastPrice?.let { formatPrice(it) } ?: state.form.entryPrice,
                    riskPercent = config.riskPercentPerTrade.toString(),
                    accountBalance = config.accountBalance.toString(),
                ),
                // Any prefill invalidates a previous result.
                outcome = null,
            )
        }
    }

    /** Editing any field clears the stale result rather than leaving it on screen. */
    fun updateForm(transform: (CalculatorForm) -> CalculatorForm) =
        _uiState.update { it.copy(form = transform(it.form), outcome = null) }

    fun setDirection(direction: Direction) = updateForm { it.copy(direction = direction) }

    fun calculate() {
        val form = _uiState.value.form
        val outcome = calculator.calculate(
            RiskAwarePositionCalculator.Request(
                symbol = form.symbol,
                direction = form.direction,
                entryPrice = form.entryPrice.toDoubleOrNull() ?: 0.0,
                stopLossPrice = form.stopLoss.toDoubleOrNull() ?: 0.0,
                takeProfitPrice = form.takeProfit.toDoubleOrNull(),
                riskPercent = form.riskPercent.toDoubleOrNull() ?: 0.0,
                accountBalance = form.accountBalance.toDoubleOrNull() ?: 0.0,
            )
        )
        _uiState.update { it.copy(outcome = outcome) }
    }

    fun reset() = _uiState.update { CalculatorUiState() }

    /** Enough decimals for FX without rendering 1.10000000000000009. */
    private fun formatPrice(value: Double): String =
        java.math.BigDecimal(value)
            .setScale(5, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
