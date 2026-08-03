package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OptimizationObjective
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class TradeProOptimizerUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val objective: OptimizationObjective = OptimizationObjective.SYSTEM_QUALITY,
    val availableSymbols: ImmutableList<String> = DEFAULT_SYMBOLS,
    val isRunning: Boolean = false,
    val error: String? = null,
    val report: TradeProOptimizationReport? = null,
    val isSynthetic: Boolean = false,
    val applied: Boolean = false,
) {
    val hasReport: Boolean get() = report != null

    companion object {
        val DEFAULT_SYMBOLS: ImmutableList<String> = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "NAS100", "US500",
        )
    }
}
