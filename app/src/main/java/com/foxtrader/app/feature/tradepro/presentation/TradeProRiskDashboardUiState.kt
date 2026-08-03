package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.CorrelationGroup
import com.foxtrader.app.domain.model.tradepro.PositionHeat
import com.foxtrader.app.domain.model.tradepro.PositionSizeResult
import com.foxtrader.app.domain.model.tradepro.RiskAlert
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable UI state for the TRADEPRO Risk Dashboard screen.
 *
 * Surfaces portfolio-level risk in real time: utilization gauge, daily P&L, open heat,
 * alert feed, correlation exposure, and the Kelly-criterion position sizer.
 */
@Immutable
data class TradeProRiskDashboardUiState(
    /** Risk budget utilization as a fraction (0f..1f). */
    val riskUtilization: Float = 0f,
    /** Net daily P&L in points. */
    val dailyPnl: Double = 0.0,
    /** Number of trades taken today. */
    val tradesTaken: Int = 0,
    /** Wins in the current session. */
    val wins: Int = 0,
    /** Losses in the current session. */
    val losses: Int = 0,
    /** Net points for the session. */
    val netPoints: Double = 0.0,
    /** Per-position heat breakdown for the open risk section. */
    val positionHeat: ImmutableList<PositionHeat> = persistentListOf(),
    /** Active risk alerts ordered by severity (most critical first). */
    val alerts: ImmutableList<RiskAlert> = persistentListOf(),
    /** Correlation clusters with active multi-instrument exposure. */
    val correlationGroups: ImmutableList<CorrelationGroup> = persistentListOf(),
    /** Symbol selected in the position sizer calculator. */
    val positionSizerSymbol: String = "EURUSD",
    /** Direction selected in the position sizer calculator. */
    val positionSizerDirection: Direction = Direction.BULLISH,
    /** Result of the position sizing calculation (null until computed). */
    val positionSizerResult: PositionSizeResult? = null,
    /** True while loading initial data. */
    val isLoading: Boolean = false,
    /** Error message, if any. */
    val error: String? = null,
) {
    companion object {
        val AVAILABLE_SYMBOLS: ImmutableList<String> = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "NAS100", "US500",
        )
    }
}
