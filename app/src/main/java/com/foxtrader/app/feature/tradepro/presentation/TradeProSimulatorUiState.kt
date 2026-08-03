package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ComplianceViolation
import com.foxtrader.app.domain.model.tradepro.SimulationPerformance
import com.foxtrader.app.domain.model.tradepro.SimulationSession
import com.foxtrader.app.domain.model.tradepro.SimulationSpeed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class TradeProSimulatorUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val speed: SimulationSpeed = SimulationSpeed.NORMAL,
    val availableSymbols: ImmutableList<String> = DEFAULT_SYMBOLS,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val isSynthetic: Boolean = false,
    val error: String? = null,
    val session: SimulationSession? = null,
    val performance: SimulationPerformance = SimulationPerformance.EMPTY,
    val lastViolation: ComplianceViolation? = null,
) {
    val hasSession: Boolean get() = session != null
    val canTrade: Boolean get() = session != null && session.openTrade == null && !session.isComplete
    val canManage: Boolean get() = session?.openTrade?.isOpen == true

    companion object {
        val DEFAULT_SYMBOLS: ImmutableList<String> = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "NAS100", "US500",
        )
    }
}
